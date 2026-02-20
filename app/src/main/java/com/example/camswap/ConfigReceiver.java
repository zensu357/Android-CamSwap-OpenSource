package com.example.camswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.example.camswap.utils.LogUtil;

public class ConfigReceiver extends BroadcastReceiver {
    public static final String ACTION_REQUEST_CONFIG = "com.example.camswap.ACTION_REQUEST_CONFIG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REQUEST_CONFIG.equals(intent.getAction())) {
            LogUtil.log("【CS-Host】收到配置请求，正在发送当前配置 config request received");
            // Instantiate ConfigManager and set context to reload config
            ConfigManager cm = new ConfigManager();
            cm.setContext(context);
            // Send broadcast response
            cm.sendConfigBroadcast(context);
        }
    }
}
