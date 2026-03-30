package biz.amjet.radio;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.ImageButton;
import android.widget.PopupMenu;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private RecyclerView      recyclerView;
    private TextInputEditText searchInput;
    private View              emptyView;
    private StreamAdapter adapter;

    /** Database-backed repository — single source of truth */
    private StreamRepository  repo;

    /** Background thread pool for DB writes and network downloads */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** File picker launcher — opens CSV files for import */
    private final ActivityResultLauncher<String[]> csvPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importCsv(uri); }
            );

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Prevent soft keyboard from appearing automatically on launch
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        recyclerView = findViewById(R.id.recyclerView);
        searchInput  = findViewById(R.id.searchInput);
        emptyView    = findViewById(R.id.emptyView);

        ImageButton btnMenu = findViewById(R.id.btnMenu);

        // Edge-to-edge: let content draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        applyStatusBarInset();

        repo = StreamRepository.getInstance(this);

        setupRecyclerView();
        setupSearch();
        btnMenu.setOnClickListener(this::showOverflowMenu);

        // Seed sample data on first run (no-op if DB already has rows)
        executor.execute(() -> {
            repo.seedIfEmpty(this);
            runOnUiThread(() -> filterList(""));
        });
    }

    // ── Status bar inset ───────────────────────────────────────────────────────

    private void applyStatusBarInset() {
        View appBar  = findViewById(R.id.appBar);
        View sidebar = findViewById(R.id.sidebarPanel);
        View target  = appBar != null ? appBar : sidebar;
        if (target == null) return;

        ViewCompat.setOnApplyWindowInsetsListener(target, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), v.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ── RecyclerView ───────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new StreamAdapter(this::openPlayer, this::confirmDelete);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    private void setupSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                filterList(s != null ? s.toString() : "");
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
    }

    // ── Filter ─────────────────────────────────────────────────────────────────

    private void filterList(String query) {
        // Run DB query on background thread, update UI on main thread
        String q = query.trim();
        executor.execute(() -> {
            List<StreamItem> results = q.isEmpty() ? repo.getAll() : repo.search(q);
            runOnUiThread(() -> {
                adapter.submitList(new ArrayList<>(results));
                emptyView.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    // ── Play ───────────────────────────────────────────────────────────────────

    private void openPlayer(StreamItem item) {
        LastStreamPrefs.save(this, item);
        Intent intent = new Intent(this, StreamPlayerActivity.class);
        intent.putExtra("stream_item", item);
        startActivity(intent);
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());

        // Show "Play Last Stream" only if a last stream exists
        popup.getMenu().findItem(R.id.menu_last_stream)
                .setVisible(LastStreamPrefs.exists(this));

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_last_stream)  { playLastStream();       return true; }
            if (id == R.id.menu_add_stream)   { showAddStreamDialog();  return true; }
            if (id == R.id.menu_import)       { showImportDialog();     return true; }
            if (id == R.id.menu_remove_all)   { confirmRemoveAll();     return true; }
            return false;
        });
        popup.show();
    }

    private void playLastStream() {
        StreamItem last = LastStreamPrefs.load(this);
        if (last == null) {
            Toast.makeText(this, getString(R.string.toast_no_last_stream), Toast.LENGTH_SHORT).show();
            return;
        }
        openPlayer(last);
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    private void confirmDelete(StreamItem item) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_delete_title))
                .setMessage(getString(R.string.dialog_delete_message, item.getTitle()))
                .setPositiveButton(getString(R.string.btn_remove), (dialog, which) -> deleteStream(item))
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void deleteStream(StreamItem item) {
        executor.execute(() -> {
            repo.delete(item.getId());
            runOnUiThread(() -> {
                String query = searchInput.getText() != null ? searchInput.getText().toString() : "";
                filterList(query);
                Toast.makeText(this, getString(R.string.toast_removed, item.getTitle()), Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ── Add ────────────────────────────────────────────────────────────────────

    private void showAddStreamDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_stream, null);
        TextInputEditText titleInput = view.findViewById(R.id.inputTitle);
        TextInputEditText urlInput   = view.findViewById(R.id.inputUrl);
        MaterialButtonToggleGroup toggleType = view.findViewById(R.id.toggleType);
        toggleType.check(R.id.btnAudio); // default to Audio

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_add_title))
                .setView(view)
                .setPositiveButton(getString(R.string.btn_play), (dialog, which) -> {
                    String title = titleInput.getText() != null
                            ? titleInput.getText().toString().trim() : "";
                    String url = urlInput.getText() != null
                            ? urlInput.getText().toString().trim() : "";
                    if (url.isEmpty()) {
                        Toast.makeText(this, getString(R.string.error_url_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (title.isEmpty()) title = getString(R.string.default_stream_title);
                    boolean isVideo = toggleType.getCheckedButtonId() == R.id.btnVideo;
                    StreamItem newItem = new StreamItem(
                            0, title, getString(R.string.custom_stream_desc), url,
                            isVideo ? StreamType.VIDEO : StreamType.AUDIO, "");
                    String finalTitle = title;
                    executor.execute(() -> {
                        long newId = repo.insert(newItem);
                        // Build item with the real DB-assigned id so we can pass it to the player
                        StreamItem saved = new StreamItem(
                                (int) newId, finalTitle,
                                getString(R.string.custom_stream_desc), url,
                                isVideo ? StreamType.VIDEO : StreamType.AUDIO, "");
                        runOnUiThread(() -> {
                            openPlayer(saved);
                            String query = searchInput.getText() != null
                                    ? searchInput.getText().toString() : "";
                            filterList(query);
                        });
                    });
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    // ── Import CSV ─────────────────────────────────────────────────────────────

    private void showImportDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_import_title))
                .setItems(
                        new CharSequence[]{
                                getString(R.string.import_source_file),
                                getString(R.string.import_source_url)
                        },
                        (dialog, which) -> {
                            if (which == 0) pickLocalFile();
                            else           showImportUrlDialog();
                        })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void pickLocalFile() {
        csvPickerLauncher.launch(new String[]{
                "text/csv", "text/comma-separated-values",
                "text/plain", "application/csv", "*/*"
        });
    }

    private void showImportUrlDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(getString(R.string.import_url_hint));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_source_url))
                .setView(input)
                .setPositiveButton(getString(R.string.btn_import), (dialog, which) -> {
                    String rawUrl = input.getText().toString().trim();
                    if (rawUrl.isEmpty()) {
                        Toast.makeText(this, getString(R.string.error_url_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    downloadAndImportCsv(rawUrl);
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void downloadAndImportCsv(String rawUrl) {
        Toast.makeText(this, getString(R.string.import_downloading), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                URL url = new URL(rawUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setRequestMethod("GET");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                List<StreamItem> imported = parseCsvStream(conn.getInputStream());
                conn.disconnect();
                saveImportedItems(imported);
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.import_error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importCsv(Uri uri) {
        executor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception("Cannot open file");
                List<StreamItem> imported = parseCsvStream(is);
                saveImportedItems(imported);
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.import_error), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Parse a 3-field CSV stream.
     *
     * Format (no header row):
     *   type,title,url
     *
     *   type  — 'A' or 'a' for AUDIO, 'V' or 'v' for VIDEO
     *   title — stream display name (may be quoted)
     *   url   — playback URL (may be quoted)
     *
     * Lines that are blank or start with # are skipped.
     */
    private List<StreamItem> parseCsvStream(InputStream is) throws Exception {
        List<StreamItem> imported = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Split into exactly 3 fields on the first two commas.
            // URL (field 3) may itself contain commas, so we split only twice.
            int first = line.indexOf(',');
            if (first < 0) continue;
            int second = line.indexOf(',', first + 1);
            if (second < 0) continue;

            String typeField  = stripQuotes(line.substring(0, first).trim());
            String titleField = stripQuotes(line.substring(first + 1, second).trim());
            String urlField   = stripQuotes(line.substring(second + 1).trim());

            if (urlField.isEmpty()) continue;
            if (titleField.isEmpty()) titleField = getString(R.string.default_stream_title);

            StreamType type;
            if (typeField.equalsIgnoreCase("V")) {
                type = StreamType.VIDEO;
            } else if (typeField.equalsIgnoreCase("A")) {
                type = StreamType.AUDIO;
            } else {
                // Unknown type field — skip this row
                continue;
            }

            imported.add(new StreamItem(0, titleField,
                    getString(R.string.custom_stream_desc), urlField, type, ""));
        }
        reader.close();
        return imported;
    }

    /** Persist imported items to DB and refresh UI — runs on background thread */
    private void saveImportedItems(List<StreamItem> imported) {
        if (imported.isEmpty()) {
            runOnUiThread(() ->
                    Toast.makeText(this, getString(R.string.import_empty), Toast.LENGTH_SHORT).show());
            return;
        }
        repo.insertAll(imported);
        runOnUiThread(() -> {
            String query = searchInput.getText() != null ? searchInput.getText().toString() : "";
            filterList(query);
            Toast.makeText(this,
                    getString(R.string.import_success, imported.size()),
                    Toast.LENGTH_SHORT).show();
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }



    // ── Remove All ─────────────────────────────────────────────────────────────

    private void confirmRemoveAll() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_remove_all_title))
                .setMessage(getString(R.string.dialog_remove_all_message))
                .setPositiveButton(getString(R.string.btn_remove), (dialog, which) -> removeAllStreams())
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void removeAllStreams() {
        executor.execute(() -> {
            repo.deleteAll();
            runOnUiThread(() -> {
                filterList("");
                Toast.makeText(this, getString(R.string.toast_all_removed), Toast.LENGTH_SHORT).show();
            });
        });
    }
}
