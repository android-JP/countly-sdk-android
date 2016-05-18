package ly.count.android.demo.useful;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

import ly.count.android.sdk.Countly;

/**
 * 嵌入了 Countly的Activity
 * Created by androidjp on 16-5-16.
 */
public class BaseActivity extends Activity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initCountly();
    }

    /**
     * create and init Countly
     */
    private void initCountly() {
        Countly.sharedInstance().setLoggingEnabled(true);

        Countly.onCreate(this);

        /** You should use cloud.count.ly instead of YOUR_SERVER for the line below if you are using Countly Cloud service */
        Countly.sharedInstance()
                .init(this, "YOUR_SERVER", "YOUR_APP_KEY");

//                .setLocation(LATITUDE, LONGITUDE);
//                .setLoggingEnabled(true);
//        setUserData(); // If UserData plugin is enabled on your server
//        enableCrashTracking();

        /**
         * 自动追踪View
         */
        Countly.sharedInstance().setViewTracking(true);

        /**
         * 设置并启用崩溃跟踪
         */
        enableCrashTracking();

    }


    /**
     * 获取手机的Android版本信息
     * @return
     */
    private String getAndroidVersionFromMobile(){
        StringBuffer sb = new StringBuffer();
        sb.append("mobile model:").append(Build.MODEL).append(" ,SDK version:")
                .append(Build.VERSION.SDK_INT).append(" ,system version:")
                .append(Build.VERSION.RELEASE);
        return sb.toString();
    }


    /**
     * 获取App PackageInfo信息
     * @return
     */
    private PackageInfo getAppVersion(){
        PackageInfo info = null;
        PackageManager manager = this.getPackageManager();
        try{
            info = manager.getPackageInfo(this.getPackageName(),0);
        }catch (PackageManager.NameNotFoundException e){
//            Countly.sharedInstance().addCrashLog(e.toString());
            Countly.sharedInstance().logException(e);
            e.printStackTrace();
        }
        return info;
    }

    /**
     * 设置用户信息
     */
    public void setUserData(){
        HashMap<String, String> data = new HashMap<String, String>();
        data.put("name", "Firstname Lastname");
        data.put("username", "nickname");
        data.put("email", "test@test.com");
        data.put("organization", "Tester");
        data.put("phone", "+123456789");
        data.put("gender", "M");
        //provide url to picture
        //data.put("picture", "http://example.com/pictures/profile_pic.png");
        //or locally from device
        //data.put("picturePath", "/mnt/sdcard/portrait.jpg");
        data.put("byear", "1987");

        //providing any custom key values to store with user
        HashMap<String, String> custom = new HashMap<String, String>();
        custom.put("country", "Turkey");
        custom.put("city", "Istanbul");
        custom.put("address", "My house 11");

        //set multiple custom properties
        Countly.userData.setUserData(data, custom);

        //set custom properties by one
        Countly.userData.setProperty("test", "test");

        //increment used value by 1
        Countly.userData.incrementBy("used", 1);

        //insert value to array of unique values
        Countly.userData.pushUniqueValue("type", "morning");

        //insert multiple values to same property
        Countly.userData.pushUniqueValue("skill", "fire");
        Countly.userData.pushUniqueValue("skill", "earth");

        Countly.userData.save();/*执行数据上传的操作*/
    }

    /**
     * 启用崩溃跟踪
     */
    public void enableCrashTracking(){
          /*给crash设置自定义的细分*/
        Map<String,String> myCustomCrashMap = new HashMap<String,String>();
        myCustomCrashMap.put("countly_version", Countly.COUNTLY_SDK_VERSION_STRING);/*当前CountlySDK版本*/
        myCustomCrashMap.put("app_version",getAppVersion().versionName);/*当前App版本号*/
        myCustomCrashMap.put("mobile_msg",getAndroidVersionFromMobile());/*当前手机的系统信息*/
        Countly.sharedInstance().setCustomCrashSegments(myCustomCrashMap);

        Countly.sharedInstance().enableCrashReporting();//启用自动视图跟踪调用 、启用崩溃报告（稍后可在服务器上对崩溃报告进行检查和解决）

    }

    @Override
    protected void onStart() {
        super.onStart();
        Countly.sharedInstance().onStart(this);
    }

    @Override
    protected void onStop() {
        Countly.sharedInstance().onStop();
        super.onStop();
    }
}

