package ly.count.android.demo.messaging;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Bundle;
import android.util.Log;

import ly.count.android.sdk.Countly;
import ly.count.android.sdk.messaging.CountlyMessaging;
import ly.count.android.sdk.messaging.Message;

public class MainActivity extends Activity {

    private BroadcastReceiver messageReceiver;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /** You should use cloud.count.ly instead of YOUR_SERVER for the line below if you are using Countly Cloud service */
        Countly.sharedInstance()
                .init(this, "YOUR_SERVER", "YOUR_APP_KEY")
                .initMessaging(this, MainActivity.class, "YOUR_PROJECT_ID(NUMBERS ONLY)", Countly.CountlyMessagingMode.TEST);/*这样能够在将消息发送至所有用户之前对消息进行测试，发布应用时，改成PRODUCE*/
        /*其中 PROJECT_NUMBER 是来自于 Google 开发者控制台的项目编号，APP_KEY 是来自于 Countly 仪表盘应用管理区的应用程序密钥。*/
//                .setLocation(LATITUDE, LONGITUDE);
//                .setLoggingEnabled(true);

        Countly.sharedInstance().recordEvent("test", 1);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Countly.sharedInstance().recordEvent("test2", 1, 2);
            }
        }, 5000);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Countly.sharedInstance().recordEvent("test3");
            }
        }, 10000);

    }

    @Override
    public void onStart()
    {
        super.onStart();
        Countly.sharedInstance().onStart(this);
    }

    @Override
    public void onStop()
    {
        Countly.sharedInstance().onStop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();


        /**
         * 在收到新消息时获得通知（可选步骤）
         * 需要注册Receiver
         */

        /** Register for broadcast action if you need to be notified when Countly message received */
        messageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Message message = intent.getParcelableExtra(CountlyMessaging.BROADCAST_RECEIVER_ACTION_MESSAGE);
                Log.i("CountlyActivity", "Got a message with data: " + message.getData());
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(CountlyMessaging.getBroadcastAction(getApplicationContext()));
        registerReceiver(messageReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(messageReceiver);
    }
}
