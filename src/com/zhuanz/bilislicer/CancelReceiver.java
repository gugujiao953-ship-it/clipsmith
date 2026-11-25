package com.zhuanz.bilislicer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知栏「取消」按钮 */
public class CancelReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent it) {
        Jobs.cancelAll();
    }
}
