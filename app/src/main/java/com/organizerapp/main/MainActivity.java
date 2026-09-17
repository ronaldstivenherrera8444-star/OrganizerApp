package com.organizerapp.main;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pantalla única de la app:
 *  - Modo normal: muestra las carpetas y cuántos proyectos tiene cada una.
 *  - Modo "archivo entrante" (cuando otra app te manda un .html vía "Abrir con"):
 *    pregunta en qué carpeta lo quieres guardar y lo registra ahí.
 *
 * Los datos (carpetas y proyectos) se guardan en SharedPreferences como JSON,
 * igual que la versión web guardaba todo en localStorage.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "organizer_prefs";
    private static final String KEY_FOLDERS = "folders";
    private static final String KEY_PROJECTS = "projects";

    private static final String[] DEFAULT_FOLDER_NAMES = {
            "Que estoy realizando", "Me gustaron", "No me gustaron", "Sirven", "No sirven"
    };
    private static final String[] DEFAULT_FOLDER_COLORS = {
            "#4B4FE0", "#4F7C52", "#B15A45", "#C07A1E", "#7C8B99"
    };

    private SharedPreferences prefs;
    private final List<JSONObject> folders = new ArrayList<>();
    private final List<JSONObject> projects = new ArrayList<>();

    private RecyclerView recyclerView;
    private FolderAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadData();
        ensureDefaultFolders();

        recyclerView = findViewById(R.id.recyclerFolders);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FolderAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btnAddFolder).setOnClickListener(v -> showAddFolderDialog());

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    // ---------- recibir el archivo desde "Abrir con" ----------

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
            String fileName = queryFileName(data);
            if (folders.isEmpty()) {
                Toast.makeText(this, "Crea una carpeta primero", Toast.LENGTH_SHORT).show();
                return;
            }
            showPickFolderDialog(fileName, data.toString());
        }
    }

    private String queryFileName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String display = cursor.getString(idx);
                    if (display != null) name = display;
                }
            }
        } catch (Exception ignored) {
            // si el proveedor no da nombre, nos quedamos con el del path
        }
        return name != null ? name : "archivo";
    }

    private void showPickFolderDialog(String fileName, String uriString) {
        String[] names = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            names[i] = folders.get(i).optString("name");
        }
        new AlertDialog.Builder(this)
                .setTitle("Guardar \"" + fileName + "\" en\u2026")
                .setItems(names, (dialog, which) -> {
                    JSONObject folder = folders.get(which);
                    addProject(fileName, uriString, folder.optString("id"));
                    Toast.makeText(this, "Guardado en " + folder.optString("name"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- carpetas ----------

    private void ensureDefaultFolders() {
        boolean changed = false;
        for (int i = 0; i < DEFAULT_FOLDER_NAMES.length; i++) {
            String name = DEFAULT_FOLDER_NAMES[i];
            boolean exists = false;
            for (JSONObject f : folders) {
                if (f.optString("name").equalsIgnoreCase(name)) { exists = true; break; }
            }
            if (!exists) {
                JSONObject f = new JSONObject();
                try {
                    f.put("id", UUID.randomUUID().toString());
                    f.put("name", name);
                    f.put("color", DEFAULT_FOLDER_COLORS[i]);
                } catch (JSONException ignored) {}
                folders.add(f);
                changed = true;
            }
        }
        if (changed) saveData();
    }

    private void showAddFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("Nombre de la carpeta");
        new AlertDialog.Builder(this)
                .setTitle("Nueva carpeta")
                .setView(input)
                .setPositiveButton("Crear", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    JSONObject f = new JSONObject();
                    try {
                        f.put("id", UUID.randomUUID().toString());
                        f.put("name", name);
                        f.put("color", "#4B4FE0");
                    } catch (JSONException ignored) {}
                    folders.add(f);
                    saveData();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showFolderContents(JSONObject folder) {
        String folderId = folder.optString("id");
        List<JSONObject> items = new ArrayList<>();
        for (JSONObject p : projects) {
            if (folderId.equals(p.optString("folderId"))) items.add(p);
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "Esta carpeta todavía está vacía", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) names[i] = items.get(i).optString("name");

        new AlertDialog.Builder(this)
                .setTitle(folder.optString("name"))
                .setItems(names, (dialog, which) -> openProject(items.get(which)))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void openProject(JSONObject project) {
        try {
            Uri uri = Uri.parse(project.optString("uri"));
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(uri, "text/html");
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el archivo (puede que ya no exista ahí)", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- proyectos ----------

    private void addProject(String name, String uriString, String folderId) {
        JSONObject p = new JSONObject();
        try {
            p.put("id", UUID.randomUUID().toString());
            p.put("name", name);
            p.put("uri", uriString);
            p.put("folderId", folderId);
            p.put("added", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date()));
        } catch (JSONException ignored) {}
        projects.add(p);
        saveData();
        adapter.notifyDataSetChanged();
    }

    // ---------- persistencia ----------

    private void loadData() {
        folders.clear();
        projects.clear();
        try {
            JSONArray f = new JSONArray(prefs.getString(KEY_FOLDERS, "[]"));
            for (int i = 0; i < f.length(); i++) folders.add(f.getJSONObject(i));
            JSONArray p = new JSONArray(prefs.getString(KEY_PROJECTS, "[]"));
            for (int i = 0; i < p.length(); i++) projects.add(p.getJSONObject(i));
        } catch (JSONException ignored) {}
    }

    private void saveData() {
        JSONArray f = new JSONArray();
        for (JSONObject o : folders) f.put(o);
        JSONArray p = new JSONArray();
        for (JSONObject o : projects) p.put(o);
        prefs.edit()
                .putString(KEY_FOLDERS, f.toString())
                .putString(KEY_PROJECTS, p.toString())
                .apply();
    }

    // ---------- adapter de la lista de carpetas ----------

    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView name, count;
            View colorDot;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.folderName);
                count = v.findViewById(R.id.folderCount);
                colorDot = v.findViewById(R.id.colorDot);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_folder, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            JSONObject folder = folders.get(position);
            String folderId = folder.optString("id");
            int c = 0;
            for (JSONObject p : projects) if (folderId.equals(p.optString("folderId"))) c++;

            holder.name.setText(folder.optString("name"));
            holder.count.setText(c + (c == 1 ? " proyecto" : " proyectos"));
            try {
                holder.colorDot.setBackgroundColor(android.graphics.Color.parseColor(folder.optString("color", "#4B4FE0")));
            } catch (Exception ignored) {}

            holder.itemView.setOnClickListener(v -> showFolderContents(folder));
        }

        @Override
        public int getItemCount() {
            return folders.size();
        }
    }
}
