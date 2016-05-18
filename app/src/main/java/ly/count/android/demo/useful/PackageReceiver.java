package ly.count.android.demo.useful;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 监听程序的安装和卸载的广播
 *
 * Created by androidjp on 16-5-17.
 */
public class PackageReceiver extends BroadcastReceiver{
    private final String Tag = "PackageReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("android.intent.action.PACKAGE_ADDED")){
            String packageName = intent.getDataString();
            Log.i(Tag, "Install finished:--->"+ packageName);
        }

        if (intent.getAction().equals("android.intent.action.PACKAGE_REMOVED")){
            String packageName = intent.getDataString();
            Log.i(Tag, "Unstall finished:--->"+ packageName);
        }
    }
}
