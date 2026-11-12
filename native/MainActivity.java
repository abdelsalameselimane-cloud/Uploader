package com.uploader.app;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShareReceiverPlugin.class);
        super.onCreate(savedInstanceState);
        // Cold start from "Share" / "Open with": handle the launching intent once
        if (savedInstanceState == null) {
            ShareReceiverPlugin.handleIntent(this, getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // App already running: a new file was shared to it
        ShareReceiverPlugin.handleIntent(this, intent);
    }
}
