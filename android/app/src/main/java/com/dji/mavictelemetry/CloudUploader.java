package com.dji.mavictelemetry;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Envia telemetria ao Firebase Realtime Database.
 *
 * O TelemetryStreamer chama onSnapshot() a 10 Hz (mesma taxa do UDP local); aqui a maior
 * parte dos pacotes é descartada para manter poucas escritas por segundo (padrão ~1 Hz),
 * já que o Realtime Database cobra/limita por banda e conexões simultâneas, não por escrita,
 * mas 10 Hz continuamente gastaria banda e bateria à toa. A tela local continua a 10 Hz.
 *
 * Cada vez que o envio é ligado, um novo "flightId" agrupa os pontos desse voo em
 * /flights/{flightId}/log (histórico, via push) e /flights/{flightId}/live (último valor).
 * Requer google-services.json (ver README) e as regras em firebase/database.rules.json.
 */
public class CloudUploader implements TelemetryStreamer.Listener {
    private static final String TAG = "CloudUploader";

    private final Context context;
    private volatile boolean enabled;
    private volatile long minIntervalMs = 1000;
    private volatile long lastUploadMs;
    private volatile String flightId;
    private volatile String status = "Nuvem desligada";
    private volatile DatabaseReference flightRef;
    private volatile DatabaseReference metaRef;
    private boolean signingIn;

    public CloudUploader(Context context) {
        this.context = context.getApplicationContext();
    }

    public String getStatus() {
        return status;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getFlightId() {
        return flightId;
    }

    /** Ajusta quantos pontos por segundo sobem para a nuvem (a captura local continua a 10 Hz). */
    public void setUploadRateHz(double hz) {
        minIntervalMs = Math.round(1000 / Math.max(hz, 0.1));
    }

    /** true se google-services.json foi processado no build (arquivo presente ao compilar). */
    public boolean isConfigured() {
        try {
            return !FirebaseApp.getApps(context).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Liga/desliga o envio. Ao ligar, gera um novo flightId agrupando este voo no banco. */
    public synchronized void setEnabled(boolean on, String newFlightId) {
        if (!on) {
            enabled = false;
            status = "Nuvem desligada";
            // Marca o voo como encerrado "de propósito" (o dashboard mostra isso em tempo real)
            // e cancela o onDisconnect — não é mais uma queda inesperada, já estamos parando.
            DatabaseReference mr = metaRef;
            if (mr != null) {
                Map<String, Object> end = new HashMap<>();
                end.put("active", false);
                end.put("endedAt", ServerValue.TIMESTAMP);
                mr.updateChildren(end);
                mr.onDisconnect().cancel();
            }
            flightRef = null;
            metaRef = null;
            return;
        }
        if (!isConfigured()) {
            enabled = false;
            status = "Firebase não configurado: falta android/app/google-services.json (veja README)";
            return;
        }
        flightId = newFlightId;
        lastUploadMs = 0;
        try {
            flightRef = FirebaseDatabase.getInstance().getReference("flights").child(flightId);
        } catch (Throwable t) {
            enabled = false;
            status = "Falha ao iniciar Firebase: " + t.getMessage();
            return;
        }
        enabled = true;
        status = "Conectando à nuvem...";
        ensureSignedIn();
    }

    private void ensureSignedIn() {
        FirebaseAuth auth;
        try {
            auth = FirebaseAuth.getInstance();
        } catch (Throwable t) {
            status = "Falha no Firebase Auth: " + t.getMessage();
            return;
        }
        if (auth.getCurrentUser() != null) {
            onSignedIn();
            return;
        }
        if (signingIn) return;
        signingIn = true;
        auth.signInAnonymously().addOnCompleteListener(task -> {
            signingIn = false;
            if (task.isSuccessful()) {
                onSignedIn();
            } else {
                status = "Falha no login anônimo do Firebase: " + task.getException();
                Log.w(TAG, "signInAnonymously failed", task.getException());
            }
        });
    }

    private void onSignedIn() {
        status = "Nuvem ativa (voo " + flightId + ")";
        DatabaseReference ref = flightRef;
        if (ref == null) return;
        DatabaseReference mr = ref.child("meta");
        metaRef = mr;
        Map<String, Object> meta = new HashMap<>();
        meta.put("startedAt", ServerValue.TIMESTAMP);
        meta.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        meta.put("active", true);
        mr.updateChildren(meta);
        // Rede de segurança: se o app cair, a bateria acabar ou o celular sair de área sem dar
        // tempo de avisar, o PRÓPRIO SERVIDOR do Firebase marca o voo como encerrado sozinho,
        // assim que perceber que a conexão caiu — não depende do celular conseguir escrever nada.
        Map<String, Object> onDisconnect = new HashMap<>();
        onDisconnect.put("active", false);
        onDisconnect.put("endedAt", ServerValue.TIMESTAMP);
        mr.onDisconnect().updateChildren(onDisconnect);
    }

    @Override
    public void onSnapshot(String json) {
        if (!enabled) return;
        DatabaseReference ref = flightRef;
        if (ref == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUploadMs < minIntervalMs) return;
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            ensureSignedIn();
            return;
        }
        lastUploadMs = now;
        try {
            Map<String, Object> map = toMap(new JSONObject(json));
            ref.child("live").setValue(map);
            ref.child("log").push().setValue(map);
        } catch (Exception e) {
            status = "Erro ao enviar para a nuvem: " + e.getMessage();
            Log.w(TAG, "upload failed", e);
        }
    }

    @Override
    public void onError(String message) {
        // Erros do envio local (UDP) não têm relação com o upload para a nuvem.
    }

    /** JSONObject -> Map recursivo; campos nulos são omitidos (RTDB trata ausência como null). */
    private static Map<String, Object> toMap(JSONObject obj) throws Exception {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = obj.get(k);
            if (v == JSONObject.NULL) continue;
            map.put(k, v instanceof JSONObject ? toMap((JSONObject) v) : v);
        }
        return map;
    }
}
