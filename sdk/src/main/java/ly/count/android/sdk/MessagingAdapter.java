package ly.count.android.sdk;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 消息辅助工具类
 */
public class MessagingAdapter {
    private static final String TAG = "MessagingAdapter";
    private final static String MESSAGING_CLASS_NAME = "ly.count.android.sdk.messaging.CountlyMessaging";


    /**
     * 如果有到导入 sdk-messaging 库，就表示 messaging 是可用的
     * @return
     */
    public static boolean isMessagingAvailable() {
        boolean messagingAvailable = false;
        try {
            Class.forName(MESSAGING_CLASS_NAME);
            messagingAvailable = true;
        }
        catch (ClassNotFoundException ignored) {}
        return messagingAvailable;
    }

    public static boolean init(Activity activity, Class<? extends Activity> activityClass, String sender, String[] buttonNames) {
        try {
            final Class<?> cls = Class.forName(MESSAGING_CLASS_NAME);
            final Method method = cls.getMethod("init", Activity.class, Class.class, String.class, String[].class);
            method.invoke(null, activity, activityClass, sender, buttonNames);
            return true;
        }
        catch (Throwable logged) {
            Log.e(TAG, "Couldn't init Countly Messaging", logged);
            return false;
        }
    }

    /**
     * 存储配置信息
     *
     * 通过反射，获取 CountlyMessaging,java 中的 storeConfiguration 方法 ，然后调用它，实现配置的保存（保存了：context，serverURL，appKey，deviceID，idMode）
     *
     * @param context
     * @param serverURL
     * @param appKey
     * @param deviceID
     * @param idMode
     * @return
     */
    public static boolean storeConfiguration(Context context, String serverURL, String appKey, String deviceID, DeviceId.Type idMode) {
        try {
            final Class<?> cls = Class.forName(MESSAGING_CLASS_NAME);
            final Method method = cls.getMethod("storeConfiguration", Context.class, String.class, String.class, String.class, DeviceId.Type.class);
            method.invoke(null, context, serverURL, appKey, deviceID, idMode);
            return true;
        }
        catch (Throwable logged) {
            Log.e(TAG, "Couldn't store configuration in Countly Messaging", logged);
            return false;
        }
    }
}
