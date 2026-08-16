package com.dsh.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public class LauncherActivity extends Activity {

    public static final String EXTRA_PORT = "com.dsh.shell.extra.PORT";

    private static final String PREFS_NAME = "dsh_prefs";
    private static final String KEY_PORT = "port";
    private static final String KEY_RUNTIME_MANIFEST_URL = "runtime_manifest_url";
    private static final int DEFAULT_PORT = 3080;

    private SharedPreferences prefs;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int port = DEFAULT_PORT;
    private boolean checking = false;
    private boolean autoEnterConsumed = false;
    private boolean autoStartAttempted = false;
    private DshProbe.Status lastStatus = null;
    private boolean conflictDialogShown = false;

    private TextView statusTitle;
    private TextView statusDetail;
    private View statusDot;
    private ProgressBar progress;
    private Button primaryButton;
    private Button secondaryButton;
    private Button retryButton;
    private Button runtimeButton;
    private Button capabilitiesButton;

    private final Runnable autoEnterRunnable = new Runnable() {
        @Override
        public void run() {
            enterWeb();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        port = prefs.getInt(KEY_PORT, DEFAULT_PORT);

        statusDot = findViewById(R.id.status_dot);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        progress = findViewById(R.id.progress);
        primaryButton = findViewById(R.id.btn_primary);
        secondaryButton = findViewById(R.id.btn_secondary);
        retryButton = findViewById(R.id.btn_retry);
        runtimeButton = findViewById(R.id.btn_runtime);
        capabilitiesButton = findViewById(R.id.btn_capabilities);

        primaryButton.setOnClickListener(v -> onPrimaryClick());
        secondaryButton.setOnClickListener(v -> showPortDialog());
        retryButton.setOnClickListener(v -> {
            autoStartAttempted = false;
            startCheck();
        });
        runtimeButton.setOnClickListener(v -> showRuntimeDialog());
        capabilitiesButton.setOnClickListener(v -> showCapabilitiesDialog());

        startCheck();
    }

    private void onPrimaryClick() {
        if (lastStatus == DshProbe.Status.RUNNING) {
            enterWeb();
        } else if (lastStatus == DshProbe.Status.CONFLICT) {
            showPortDialog();
        } else {
            startDsh();
        }
    }

    private void enterWeb() {
        autoEnterConsumed = true;
        handler.removeCallbacks(autoEnterRunnable);
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(EXTRA_PORT, port);
        startActivity(intent);
    }

    private void startCheck() {
        if (checking) return;
        checking = true;
        autoEnterConsumed = false;
        conflictDialogShown = false;
        handler.removeCallbacks(autoEnterRunnable);
        progress.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.launcher_checking);
        statusDetail.setText(getString(R.string.launcher_checking_detail, port));
        setDot(DshProbe.Status.NOT_RUNNING);
        primaryButton.setEnabled(false);
        retryButton.setEnabled(false);

        new Thread(() -> {
            DshProbe.Result result = DshProbe.probe(port);
            runOnUiThread(() -> onProbeResult(result));
        }).start();
    }

    private void onProbeResult(DshProbe.Result result) {
        checking = false;
        progress.setVisibility(View.GONE);
        primaryButton.setEnabled(true);
        retryButton.setEnabled(true);
        lastStatus = result.status;

        switch (result.status) {
            case RUNNING:
                secondaryButton.setOnClickListener(v -> showPortDialog());
                setDot(DshProbe.Status.RUNNING);
                statusTitle.setText(R.string.launcher_running_title);
                statusDetail.setText(getString(R.string.launcher_running_detail, port));
                primaryButton.setText(R.string.launcher_enter);
                secondaryButton.setText(R.string.launcher_change_port);
                secondaryButton.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.VISIBLE);
                // 一键无脑入：检测通过后自动进入
                if (!autoEnterConsumed) {
                    handler.postDelayed(autoEnterRunnable, 700);
                }
                break;
            case NOT_RUNNING:
                secondaryButton.setOnClickListener(v -> showPortDialog());
                setDot(DshProbe.Status.NOT_RUNNING);
                statusTitle.setText(R.string.launcher_not_running_title);
                statusDetail.setText(getString(R.string.launcher_not_running_detail, port));
                primaryButton.setText(R.string.launcher_start_dsh);
                secondaryButton.setText(R.string.launcher_change_port);
                secondaryButton.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.VISIBLE);
                // 首次进入直接准备内置运行时并启动 dsh，不再要求 Termux/root。
                if (!autoStartAttempted) {
                    autoStartAttempted = true;
                    handler.postDelayed(this::startDsh, 250);
                }
                break;
            case CONFLICT:
                secondaryButton.setOnClickListener(v -> showPortDialog());
                setDot(DshProbe.Status.CONFLICT);
                statusTitle.setText(R.string.launcher_conflict_title);
                statusDetail.setText(getString(R.string.launcher_conflict_detail, port));
                primaryButton.setText(R.string.launcher_change_port);
                secondaryButton.setVisibility(View.GONE);
                retryButton.setVisibility(View.VISIBLE);
                if (!conflictDialogShown) {
                    conflictDialogShown = true;
                    showPortConflictDialog();
                }
                break;
        }
    }

    private void setDot(DshProbe.Status status) {
        int drawable;
        switch (status) {
            case RUNNING:
                drawable = R.drawable.dot_green;
                break;
            case CONFLICT:
                drawable = R.drawable.dot_orange;
                break;
            case NOT_RUNNING:
            default:
                drawable = R.drawable.dot_red;
                break;
        }
        statusDot.setBackgroundResource(drawable);
    }

    private void showPortDialog() {
        showPortDialog(false);
    }

    private void showPortConflictDialog() {
        showPortDialog(true);
    }

    private void showPortDialog(boolean fromConflict) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(String.valueOf(DEFAULT_PORT));
        input.setText(String.valueOf(port));
        input.setPadding(32, 16, 32, 16);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(fromConflict ? R.string.launcher_conflict_dialog_title : R.string.launcher_port_dialog_title)
                .setMessage(fromConflict ? getString(R.string.launcher_conflict_dialog_message, port) : getString(R.string.launcher_port_dialog_message))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.launcher_save_and_retry, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    int newPort = parseIntSafe(text, -1);
                    if (newPort < 1 || newPort > 65535) {
                        Toast.makeText(this, R.string.launcher_port_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    port = newPort;
                    prefs.edit().putInt(KEY_PORT, port).apply();
                    startCheck();
                });
        builder.show();
    }

    private int parseIntSafe(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showRuntimeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 24);
        box.setPadding(pad, 0, pad, 0);

        TextView status = new TextView(this);
        status.setText(getString(
                R.string.runtime_status,
                DshRuntime.bundledVersion(),
                DshRuntime.installedVersion(this)));
        status.setTextColor(getResources().getColor(R.color.text_secondary));
        status.setTextSize(13);
        box.addView(status);

        EditText manifest = new EditText(this);
        manifest.setSingleLine(true);
        manifest.setHint(R.string.runtime_manifest_hint);
        manifest.setText(prefs.getString(KEY_RUNTIME_MANIFEST_URL, ""));
        manifest.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(manifest);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.runtime_dialog_title)
                .setView(box)
                .setNegativeButton(R.string.runtime_cancel, null)
                .setNeutralButton(R.string.runtime_repair, null)
                .setPositiveButton(R.string.runtime_check, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                repairBundledRuntime();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url = manifest.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(this, R.string.runtime_manifest_hint, Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString(KEY_RUNTIME_MANIFEST_URL, url).apply();
                dialog.dismiss();
                checkAndUpdateRuntime(url);
            });
        });
        dialog.show();
    }

    private void showCapabilitiesDialog() {
        DshCapabilities.Settings current = DshCapabilities.load(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 24);
        box.setPadding(pad, 0, pad, 0);

        TextView intro = new TextView(this);
        intro.setText(R.string.capabilities_intro);
        intro.setTextColor(getResources().getColor(R.color.text_secondary));
        intro.setTextSize(13);
        box.addView(intro);

        Switch permission = addCapabilitySwitch(box, R.string.capability_permission, R.string.capability_proot_detail, current.permissionPrompts);
        Switch sandbox = addCapabilitySwitch(box, R.string.capability_sandbox, R.string.capability_proot_detail, current.sandbox);
        Switch bashSandbox = addCapabilitySwitch(box, R.string.capability_bash_sandbox, R.string.capability_proot_detail, current.bashSandbox);
        Switch rootShell = addCapabilitySwitch(box, R.string.capability_root, R.string.capability_root_detail, current.rootShell);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.capabilities_title)
                .setView(box)
                .setNegativeButton(R.string.runtime_cancel, null)
                .setPositiveButton(R.string.capabilities_save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean useSandbox = sandbox.isChecked() || bashSandbox.isChecked() || permission.isChecked();
            boolean useBashSandbox = bashSandbox.isChecked() || permission.isChecked();
            DshCapabilities.Settings settings = new DshCapabilities.Settings(
                    permission.isChecked(), useSandbox, useBashSandbox, rootShell.isChecked());
            if (settings.rootShell && !DshCapabilities.rootAvailable()) {
                rootShell.setChecked(false);
                Toast.makeText(this, R.string.capability_root_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                DshCapabilities.save(this, settings);
                dialog.dismiss();
                Toast.makeText(this, R.string.capabilities_saved, Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Toast.makeText(this, getString(R.string.capabilities_save_failed, readableError(e)), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private Switch addCapabilitySwitch(LinearLayout parent, int titleRes, int detailRes, boolean checked) {
        Switch control = new Switch(this);
        control.setText(titleRes);
        control.setTextColor(getResources().getColor(R.color.text_primary));
        control.setTextSize(15);
        control.setPadding(0, 18, 0, 2);
        control.setChecked(checked);
        control.setOnClickListener(v -> {
            if (!control.isChecked()) return;
            boolean root = titleRes == R.string.capability_root;
            String message = getString(root ? R.string.capability_root_warning : R.string.capability_warning);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.capability_warning_title)
                    .setMessage(message)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> control.setChecked(false))
                    .setPositiveButton(R.string.capability_enable, (dialog, which) -> {
                        if (root) showRootAuthorizationConfirmation(control);
                    })
                    .setOnCancelListener(dialog -> control.setChecked(false))
                    .show();
        });
        parent.addView(control);

        TextView detail = new TextView(this);
        detail.setText(detailRes);
        detail.setTextColor(getResources().getColor(R.color.text_secondary));
        detail.setTextSize(12);
        detail.setPadding(0, 0, 0, 8);
        parent.addView(detail);
        return control;
    }

    private void showRootAuthorizationConfirmation(Switch control) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.capability_root_confirm_title)
                .setMessage(R.string.capability_root_confirm_message)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> control.setChecked(false))
                .setPositiveButton(R.string.capability_root_confirm, (dialog, which) -> {
                    control.setEnabled(false);
                    new Thread(() -> {
                        boolean available = DshCapabilities.rootAvailable();
                        runOnUiThread(() -> {
                            control.setEnabled(true);
                            if (!available) {
                                control.setChecked(false);
                                Toast.makeText(this, R.string.capability_root_unavailable, Toast.LENGTH_LONG).show();
                            }
                        });
                    }).start();
                })
                .setOnCancelListener(dialog -> control.setChecked(false))
                .show();
    }

    private void repairBundledRuntime() {
        progress.setIndeterminate(false);
        progress.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.runtime_repair);
        new Thread(() -> {
            try {
                DshRuntime.repairBundled(this, (message, percent) -> runOnUiThread(() -> {
                    statusDetail.setText(message);
                    progress.setProgress(percent);
                }));
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.runtime_repair_done, Toast.LENGTH_SHORT).show();
                    startCheck();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.runtime_update_failed, readableError(e)), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void checkAndUpdateRuntime(String manifestUrl) {
        progress.setIndeterminate(false);
        progress.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.runtime_check);
        new Thread(() -> {
            try {
                DshRuntimeUpdater.UpdateInfo info = DshRuntimeUpdater.fetchManifest(manifestUrl);
                String current = DshRuntime.installedVersion(this);
                if (info.version.equals(current)) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        Toast.makeText(this, getString(R.string.runtime_no_update, info.version), Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                runOnUiThread(() -> statusDetail.setText(getString(R.string.runtime_update_found, info.version)));
                DshRuntimeUpdater.downloadAndInstall(this, info, (message, percent) -> runOnUiThread(() -> {
                    statusDetail.setText(message);
                    progress.setProgress(percent);
                }));
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.runtime_update_done, Toast.LENGTH_LONG).show();
                    startCheck();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.runtime_update_failed, readableError(e)), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    private void startDsh() {
        statusTitle.setText(R.string.launcher_starting_title);
        statusDetail.setText(R.string.launcher_preparing_runtime);
        progress.setVisibility(View.VISIBLE);
        primaryButton.setEnabled(false);
        retryButton.setEnabled(false);

        new Thread(() -> {
            try {
                DshRuntime.ensureInstalled(this, (message, percent) -> runOnUiThread(() -> {
                    statusDetail.setText(message);
                    progress.setProgress(percent);
                }));
                runOnUiThread(() -> statusDetail.setText(getString(R.string.launcher_start_ok_detail, port)));
                DshRuntime.start(this, port);
                if (DshRuntime.isCompatibilityFallback()) {
                    runOnUiThread(() -> Toast.makeText(
                            this,
                            R.string.capability_proot_fallback_warning,
                            Toast.LENGTH_LONG).show());
                }
                runOnUiThread(() -> waitForDsh(0));
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> showStartFailed(reason));
            }
        }).start();
    }

    private void waitForDsh(int attempt) {
        if (attempt >= 30) {
            showStartFailed(getString(R.string.launcher_start_failed_detail, DshRuntime.logFile(this).getAbsolutePath()));
            return;
        }
        new Thread(() -> {
            DshProbe.Result result = DshProbe.probe(port);
            runOnUiThread(() -> {
                if (result.status == DshProbe.Status.RUNNING) {
                    onProbeResult(result);
                } else if (DshRuntime.isRunning()) {
                    statusTitle.setText(R.string.launcher_starting_title);
                    statusDetail.setText(getString(R.string.launcher_waiting_dsh, attempt + 1));
                    handler.postDelayed(() -> waitForDsh(attempt + 1), 1000);
                } else {
                    showStartFailed(getString(R.string.launcher_start_failed_detail, DshRuntime.logFile(this).getAbsolutePath()));
                }
            });
        }).start();
    }

    private void showStartFailed(String reason) {
        progress.setVisibility(View.GONE);
        primaryButton.setEnabled(true);
        retryButton.setEnabled(true);
        lastStatus = DshProbe.Status.NOT_RUNNING;
        setDot(DshProbe.Status.NOT_RUNNING);
        statusTitle.setText(R.string.launcher_start_failed_title);
        statusDetail.setText(reason);
        primaryButton.setText(R.string.launcher_retry_start);
        secondaryButton.setText(R.string.launcher_change_port);
        secondaryButton.setOnClickListener(v -> showPortDialog());
        secondaryButton.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoEnterRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastStatus == DshProbe.Status.RUNNING && !autoEnterConsumed) {
            handler.postDelayed(autoEnterRunnable, 700);
        }
    }
}
