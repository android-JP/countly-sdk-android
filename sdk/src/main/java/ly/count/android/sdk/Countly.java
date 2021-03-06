/*
Copyright (c) 2012, 2013, 2014 Countly

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/
package ly.count.android.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This class is the public API for the Countly Android SDK.
 * Get more details <a href="https://github.com/Countly/countly-sdk-android">here</a>.
 */
public class Countly {

    /**
     * Current version of the Count.ly Android SDK as a displayable string.
     */
    public static final String COUNTLY_SDK_VERSION_STRING = "16.02.02";
    /**
     * Default string used in the begin session metrics if the
     * app version cannot be found.
     */
    public static final String DEFAULT_APP_VERSION = "1.0";
    /**
     * Tag used in all logging in the Count.ly SDK.
     */
    public static final String TAG = "Countly";

    /**
     * Determines how many custom events can be queued locally before
     * an attempt is made to submit them to a Count.ly server.
     */
    private static final int EVENT_QUEUE_SIZE_THRESHOLD = 10;
    /**
     * How often onTimer() is called.
     */
    private static final long TIMER_DELAY_IN_SECONDS = 60;

    protected static List<String> publicKeyPinCertificates;

    /**
     * Enum used in Countly.initMessaging() method which controls what kind of
     * app installation it is. Later (in Countly Dashboard or when calling Countly API method),
     * you'll be able to choose whether you want to send a message to test devices,
     * or to production ones.
     */
    public static enum CountlyMessagingMode {
        TEST,
        PRODUCTION,
    }

    // see http://stackoverflow.com/questions/7048198/thread-safe-singletons-in-java
    private static class SingletonHolder {
        static final Countly instance = new Countly();
    }

    private ConnectionQueue connectionQueue_;
    @SuppressWarnings("FieldCanBeLocal")
    private ScheduledExecutorService timerService_;
    private EventQueue eventQueue_;
    private long prevSessionDurationStartTime_;
    private int activityCount_;
    private boolean disableUpdateSessionRequests_;
    private boolean enableLogging_;
    private Countly.CountlyMessagingMode messagingMode_;
    private Context context_;

    //user data access
    public static UserData userData;

    //track views
    private String lastView = null;
    private int lastViewStart = 0;
    private boolean firstView = true;
    private boolean autoViewTracker = false;

    /**
     * Returns the Countly singleton.
     */
    public static Countly sharedInstance() {
        return SingletonHolder.instance;
    }

    /**
     *
     * 创建一个Countly对象：
     * 创建一个新的请求队列 和初始化
     *
     * Constructs a Countly object.
     * Creates a new ConnectionQueue and initializes the session timer.
     */
    Countly() {
        connectionQueue_ = new ConnectionQueue();
        Countly.userData = new UserData(connectionQueue_);
        timerService_ = Executors.newSingleThreadScheduledExecutor();
        timerService_.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                onTimer();
            }
        }, TIMER_DELAY_IN_SECONDS, TIMER_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    }


    /**
     *
     * 初始化SDK ，这里会上传几个参数，包括serverURL（服务器网址）、appKey、设备标识ID生成策略选择： 优先选择使用OpenUDID生成设备ID/其次选择Google Advertising ID生成设备ID
     *
     * Initializes the Countly SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * Device ID is supplied by OpenUDID service if available, otherwise Advertising ID is used.
     * BE CAUTIOUS!!!! If neither OpenUDID, nor Advertising ID is available, Countly will ignore this user.
     * @param context application context
     * @param serverURL URL of the Countly server to submit data to; use "https://cloud.count.ly" for Countly Cloud
     * @param appKey app key for the application being tracked; find in the Countly Dashboard under Management &gt; Applications
     * @return Countly instance for easy method chaining
     * @throws java.lang.IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws java.lang.IllegalStateException if the Countly SDK has already been initialized
     */
    public Countly init(final Context context, final String serverURL, final String appKey) {
        return init(context, serverURL, appKey, null, OpenUDIDAdapter.isOpenUDIDAvailable() ? DeviceId.Type.OPEN_UDID : DeviceId.Type.ADVERTISING_ID);
    }

    /**
     * 如果有设备ID ，最后一个参数“deviceID”直接输入设备ID （每个设备唯一）
     *
     * Initializes the Countly SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * @param context application context
     * @param serverURL URL of the Countly server to submit data to; use "https://cloud.count.ly" for Countly Cloud
     * @param appKey app key for the application being tracked; find in the Countly Dashboard under Management &gt; Applications
     * @param deviceID unique ID for the device the app is running on; note that null in deviceID means that Countly will fall back to OpenUDID, then, if it's not available, to Google Advertising ID
     * @return Countly instance for easy method chaining
     * @throws IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws IllegalStateException if init has previously been called with different values during the same application instance
     */
    public Countly init(final Context context, final String serverURL, final String appKey, final String deviceID) {
        return init(context, serverURL, appKey, deviceID, null);
    }

    /**
     * 几个init方法的最终调用方法：
     * Activity.onCreate()中调用
     * 注意：在调用之后，才可以调用其他的SDK方法（除了Countly.onCreate()之外）,并且，需要在Activity的onStart() 和 onStop()方法中也需要相应地调用Countly.onStart()和Countly.onStop()
     *
     *
     * Initializes the Countly SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * @param context application context
     * @param serverURL URL of the Countly server to submit data to; use "https://cloud.count.ly" for Countly Cloud
     * @param appKey app key for the application being tracked; find in the Countly Dashboard under Management &gt; Applications
     * @param deviceID unique ID for the device the app is running on; note that null in deviceID means that Countly will fall back to OpenUDID, then, if it's not available, to Google Advertising ID
     * @param idMode enum value specifying which device ID generation strategy Countly should use: OpenUDID or Google Advertising ID
     * @return Countly instance for easy method chaining
     * @throws IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws IllegalStateException if init has previously been called with different values during the same application instance
     */
    public synchronized Countly init(final Context context, final String serverURL, final String appKey, final String deviceID, DeviceId.Type idMode) {
        /*除了deviceID 可以为null，其他的几个参数必须非空*/

        if (context == null) {
            throw new IllegalArgumentException("valid context is required");
        }
        if (!isValidURL(serverURL)) {
            throw new IllegalArgumentException("valid serverURL is required");
        }
        if (appKey == null || appKey.length() == 0) {
            throw new IllegalArgumentException("valid appKey is required");
        }
        if (deviceID != null && deviceID.length() == 0) {
            throw new IllegalArgumentException("valid deviceID is required");
        }
        /*如果deviceID 为 null 并且 设备标识也没有选择，那么，按照 优先级：OpenUDID > Google Advertising ID ，一次去两个Adapter中判断是否可用来选择默认的deviceID生成策略*/
        if (deviceID == null && idMode == null) {
            if (OpenUDIDAdapter.isOpenUDIDAvailable()) idMode = DeviceId.Type.OPEN_UDID;
            else if (AdvertisingIdAdapter.isAdvertisingIdAvailable()) idMode = DeviceId.Type.ADVERTISING_ID;
        }

        /*如果选择OpenUDID策略，但是没有这个OpenUDID_manager类存在，即无法生成openUDID ，则抛出异常，说不可用openUDID*/
        if (deviceID == null && idMode == DeviceId.Type.OPEN_UDID && !OpenUDIDAdapter.isOpenUDIDAvailable()) {
            throw new IllegalArgumentException("valid deviceID is required because OpenUDID is not available");
        }
        /*Google Advertising ID同样的情况*/
        if (deviceID == null && idMode == DeviceId.Type.ADVERTISING_ID && !AdvertisingIdAdapter.isAdvertisingIdAvailable()) {
            throw new IllegalArgumentException("valid deviceID is required because Advertising ID is not available (you need to include Google Play services 4.0+ into your project)");
        }

        /*如果 事件队列不为空 并且 1.serverURL 2.appKey 这两个数有不对劲的地方 或者 不满足 请求队列中的deviceId的id与传于的deviceId等满足全null或者相等的安全条件，
        * 那么，也抛出异常
        * */
        if (eventQueue_ != null && (!connectionQueue_.getServerURL().equals(serverURL) ||
                                    !connectionQueue_.getAppKey().equals(appKey) ||
                                    !DeviceId.deviceIDEqualsNullSafe(deviceID, idMode, connectionQueue_.getDeviceId()) )) {
            throw new IllegalStateException("Countly cannot be reinitialized with different values");
        }


        /**
         * 这里，某些情况下，CountlyMessaging 做一些后台处理，这就需要CountlyMessaging自己能够启用Countly，就需要存储配置信息，供其调用
         * 【利用MessagingAdapter的方法，反射调用 sdk-messaging包中的CountlyMessaging 工具类的静态方法以 spf的方式进行配置存储】
         */
        // In some cases CountlyMessaging does some background processing, so it needs a way
        // to start Countly on itself
        if (MessagingAdapter.isMessagingAvailable()) {
            MessagingAdapter.storeConfiguration(context, serverURL, appKey, deviceID, idMode);
        }


        /**
         * 到这一步，如果事件队列不为空，初始化会再以同样的参数执行一次
         * 所以，到了这一步，一般，事件队列都会为null
         * */
        // if we get here and eventQueue_ != null, init is being called again with the same values,
        // so there is nothing to do, because we are already initialized with those values
        if (eventQueue_ == null) {

            /*1.根据生成策略，生成一个DeviceId*/
            DeviceId deviceIdInstance;
            if (deviceID != null) {
                deviceIdInstance = new DeviceId(deviceID);
            } else {
                deviceIdInstance = new DeviceId(idMode);
            }

            /*初始化持久层对象（里面初始化并获取了 Countly_Store spf 文件）*/
            final CountlyStore countlyStore = new CountlyStore(context);

            /*让 deviceId对象初始化（给到持久层对象）*/
            deviceIdInstance.init(context, countlyStore, true);

            /**
             * 将CountlyStore的引用同时给到：请求队列和事件队列
             * (init的时候，才会去初始化eventQueue，所以，只有在init之后，才能去做事件记录)
             */

            connectionQueue_.setServerURL(serverURL);
            connectionQueue_.setAppKey(appKey);
            connectionQueue_.setCountlyStore(countlyStore);
            connectionQueue_.setDeviceId(deviceIdInstance);

            eventQueue_ = new EventQueue(countlyStore);
        }

        context_ = context;

        // context is allowed to be changed on the second init call
        connectionQueue_.setContext(context);

        return this;
    }

    /**
     * Checks whether Countly.init has been already called.
     * @return true if Countly is ready to use
     */
    public synchronized boolean isInitialized() {
        return eventQueue_ != null;
    }

    /**
     * Initializes the Countly MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param mode whether this app installation is a test release or production
     * @return Countly instance for easy method chaining
     * @throws IllegalStateException if no CountlyMessaging class is found (you need to use countly-messaging-sdk-android library instead of countly-sdk-android)
     */
    public Countly initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, Countly.CountlyMessagingMode mode) {
        return initMessaging(activity, activityClass, projectID, null, mode);
    }
    /**
     * Initializes the Countly MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param buttonNames Strings to use when displaying Dialogs (uses new String[]{"Open", "Review"} by default)
     * @param mode whether this app installation is a test release or production
     * @return Countly instance for easy method chaining
     * @throws IllegalStateException if no CountlyMessaging class is found (you need to use countly-messaging-sdk-android library instead of countly-sdk-android)
     */
    public synchronized Countly initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, String[] buttonNames, Countly.CountlyMessagingMode mode) {
        if (mode != null && !MessagingAdapter.isMessagingAvailable()) {
            throw new IllegalStateException("you need to include countly-messaging-sdk-android library instead of countly-sdk-android if you want to use Countly Messaging");
        } else {
            messagingMode_ = mode;
            if (!MessagingAdapter.init(activity, activityClass, projectID, buttonNames)) {
                throw new IllegalStateException("couldn't initialize Countly Messaging");
            }
        }

        if (MessagingAdapter.isMessagingAvailable()) {
            MessagingAdapter.storeConfiguration(connectionQueue_.getContext(), connectionQueue_.getServerURL(), connectionQueue_.getAppKey(), connectionQueue_.getDeviceId().getId(), connectionQueue_.getDeviceId().getType());
        }

        return this;
    }

    /**
     * Immediately disables session &amp; event tracking and clears any stored session &amp; event data.
     * This API is useful if your app has a tracking opt-out switch, and you want to immediately
     * disable tracking when a user opts out. The onStart/onStop/recordEvent methods will throw
     * IllegalStateException after calling this until Countly is reinitialized by calling init
     * again.
     */
    public synchronized void halt() {
        eventQueue_ = null;
        final CountlyStore countlyStore = connectionQueue_.getCountlyStore();
        if (countlyStore != null) {
            countlyStore.clear();
        }
        connectionQueue_.setContext(null);
        connectionQueue_.setServerURL(null);
        connectionQueue_.setAppKey(null);
        connectionQueue_.setCountlyStore(null);
        prevSessionDurationStartTime_ = 0;
        activityCount_ = 0;
    }

    /**
     *
     * 告诉Countly SDK，Activity已经启动了，由于Android系统没有一个简单的方式去决定什么时候一个应用实例start和stop
     * ，所有你必须在每一个Activity的()方法中调用这个方法，为了精确的应用会话追踪
     *
     * 1：必须保证init方法已经被调用
     * 2：Activity数量统计加一（如果原本没有Activity，这是第一个加进来的Activity，那么 ，将 begin_session=1 等参数给到事件中，然后保存事件，然后让处理器来发送请求给服务端）
     * 3：检查这里是否有更新的上线数据，有，就给到请求队列去处理，然后从存储中删掉记录
     * 4:标注app位于前台
     * 5:如果还设置了view跟踪，那么，可以记录下当前所在的界面（最终作为一个普通的事件进行提交保存）【首个view的提交，多一个细分参数“start=1”】
     *
     *
     * Tells the Countly SDK that an Activity has started. Since Android does not have an
     * easy way to determine when an application instance starts and stops, you must call this
     * method from every one of your Activity's onStart methods for accurate application
     * session tracking.
     * @throws IllegalStateException if Countly SDK has not been initialized
     */
    public synchronized void onStart(Activity activity) {
        appLaunchDeepLink = false;
        if (eventQueue_ == null) {
            throw new IllegalStateException("init must be called before onStart");
        }

        ++activityCount_;
        if (activityCount_ == 1) {
            onStartHelper();
        }

        /**
         * 检查这里是否有更新的上线数据，有，就给到请求队列去处理，然后从存储中删掉记录
         * TODO：这里不太理解
         */
        //check if there is an install referrer data
        String referrer = ReferrerReceiver.getReferrer(context_);
        if (Countly.sharedInstance().isLoggingEnabled()) {
            Log.d(Countly.TAG, "Checking referrer: " + referrer);
        }
        if(referrer != null){
            connectionQueue_.sendReferrerData(referrer);
            ReferrerReceiver.deleteReferrer(context_);
        }

        /*标志此时app是在前台的*/
        CrashDetails.inForeground();

        /**
         * 如果还设置了view跟踪，那么，可以记录下当前所在的界面（最终作为一个普通的事件进行提交保存）【首个view的提交，多一个细分参数“start=1”】
         */
        if(autoViewTracker){
            recordView(activity.getClass().getName());
        }
    }

    /**
     * 当第一个Activity启动的时候调用
     * 发送一个启动会话事件给服务器，同时初始化应用会话跟踪
     *
     * 设定会话开始时刻为当前
     *
     * Called when the first Activity is started. Sends a begin session event to the server
     * and initializes application session tracking.
     */
    void onStartHelper() {
        prevSessionDurationStartTime_ = System.nanoTime();/*返回最准确的系统计时器的当前值，以纳秒作为单位【这个时间每次取都可以保证不一样，但是不能用于计算当前日期】*/
        connectionQueue_.beginSession();/*请求队列调用了beginSession,将“begin_session=1”的参数给到事件中，然后保存事件，最后将事件给到请求处理器到后台执行*/
    }

    /**
     * 告诉Countly SDK Activity已经停止了。
     * （当最后一个Activity都停止后，总的activity数量为0，此时，上传会话终止请求，并处理完没有发送到服务器的事件集）
     * 标志现在app处于后台状态
     *
     *
     * Tells the Countly SDK that an Activity has stopped. Since Android does not have an
     * easy way to determine when an application instance starts and stops, you must call this
     * method from every one of your Activity's onStop methods for accurate application
     * session tracking.
     * @throws IllegalStateException if Countly SDK has not been initialized, or if
     *                               unbalanced calls to onStart/onStop are detected
     */
    public synchronized void onStop() {
        if (eventQueue_ == null) {
            throw new IllegalStateException("init must be called before onStop");
        }
        if (activityCount_ == 0) {
            throw new IllegalStateException("must call onStart before onStop");
        }

        --activityCount_;
        if (activityCount_ == 0) {
            //TODO：让请求队列发送会话终止请求 ，并处理完所有没有发送到服务器的事件集
            onStopHelper();
        }


        CrashDetails.inBackground();

        //report current view duration
        reportViewDuration();
    }

    /**
     * 在最后一个Activity被停止的时候调用：
     *
     * 1:让请求队列停止会话（将停止会话的请求发送给服务端）
     * 2:重置 会话开始时刻为0
     * 3:如果事件队列中还存在事件，就让请求队列去记录事件并处理事件
     * Called when final Activity is stopped. Sends an end session event to the server,
     * also sends any unsent custom events.
     */
    void onStopHelper() {
        connectionQueue_.endSession(roundedSecondsSinceLastSessionDurationUpdate());
        prevSessionDurationStartTime_ = 0;

        if (eventQueue_.size() > 0) {
            connectionQueue_.recordEvents(eventQueue_.events());
        }
    }

    /**
     * Called when GCM Registration ID is received. Sends a token session event to the server.
     */
    public void onRegistrationId(String registrationId) {
        connectionQueue_.tokenSession(registrationId, messagingMode_);
    }

    /**
     *  记录一个自定义事件（事件发生次数：1 ，总计（总金额）：0）
     *
     * Records a custom event with no segmentation values, a count of one and a sum of zero.
     * @param key name of the custom event, required, must not be the empty string
     * @throws IllegalStateException if Countly SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    public void recordEvent(final String key) {
        recordEvent(key, null, 1, 0);
    }

    /**
     * 记录一个自定义事件（事件发生次数：count ，总计（总金额）：0）
     *
     *
     * Records a custom event with no segmentation values, the specified count, and a sum of zero.
     * @param key name of the custom event, required, must not be the empty string
     * @param count count to associate with the event, should be more than zero
     * @throws IllegalStateException if Countly SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    public void recordEvent(final String key, final int count) {
        recordEvent(key, null, count, 0);
    }

    /**
     *
     * 记录一个自定义事件（事件发生次数：count ，总计（总金额）：sum）
     *
     * Records a custom event with no segmentation values, and the specified count and sum.
     * @param key name of the custom event, required, must not be the empty string
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @throws IllegalStateException if Countly SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    public void recordEvent(final String key, final int count, final double sum) {
        recordEvent(key, null, count, sum);
    }

    /**
     * 记录一个自定义事件（细分：segmentation ，事件发生次数：count ，总计（总金额）：0）
     *
     * Records a custom event with the specified segmentation values and count, and a sum of zero.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @throws IllegalStateException if Countly SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    public void recordEvent(final String key, final Map<String, String> segmentation, final int count) {
        recordEvent(key, segmentation, count, 0);
    }

    /**
     * 最终调用的 recordEvent
     *
     * 记录一个自定义事件（细分：segmentation，事件发生次数：count ，总计（总金额）：sum）
     *
     * 1：逐个关键参数检查是否合法
     * 2：保存到文件中
     * 3：查看是否达到10个事件或以上，是，则提交请求给后台进行处理
     *
     * Records a custom event with the specified values.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @throws IllegalStateException if Countly SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if
     *                                  segmentation contains null or empty keys or values
     */
    public synchronized void recordEvent(final String key, final Map<String, String> segmentation, final int count, final double sum) {
        if (!isInitialized()) {
            throw new IllegalStateException("Countly.sharedInstance().init must be called before recordEvent");
        }
        if (key == null || key.length() == 0) {
            throw new IllegalArgumentException("Valid Countly event key is required");
        }
        if (count < 1) {
            throw new IllegalArgumentException("Countly event count should be greater than zero");
        }
        if (segmentation != null) {
            for (String k : segmentation.keySet()) {
                if (k == null || k.length() == 0) {
                    throw new IllegalArgumentException("Countly event segmentation key cannot be null or empty");
                }
                if (segmentation.get(k) == null || segmentation.get(k).length() == 0) {
                    throw new IllegalArgumentException("Countly event segmentation value cannot be null or empty");
                }
            }
        }

        /*把事件添加进本地的spf文件中*/
        eventQueue_.recordEvent(key, segmentation, count, sum);
        /*如果本地事件队列中的事件数量达到10个或以上，就提交所有的事件*/
        sendEventsIfNeeded();
    }

    /**
     * Enable or disable automatic view tracking
     * @param enable boolean for the state of automatic view tracking
     */
    public synchronized Countly setViewTracking(boolean enable){
        autoViewTracker = enable;
        return this;
    }

    /**
     * Check state of automatic view tracking
     * @return boolean - true if enabled, false if disabled
     */
    public synchronized boolean isViewTrackingEnabled(){
        return autoViewTracker;
    }

    /**
     * 手动地记录一个view，在 没有自动跟踪 或者 不是自动跟踪向fragment、MessageBox或者透明Activity时
     *
     * 1：将 view的名称、当前时间等参数写到细分中
     * 2：作为一个普通事件那样提交保存
     *
     *  Record a view manualy, without automatic tracking
     * or track view that is not automatically tracked
     * like fragment, Message box or transparent Activity
     * @param viewName String - name of the view
     */
    public synchronized Countly recordView(String viewName){
        reportViewDuration();
        lastView = viewName;
        lastViewStart = Countly.currentTimestamp();
        HashMap<String, String> segments = new HashMap<String, String>();
        segments.put("name", viewName);
        segments.put("visit", "1");
        segments.put("segment", "Android");
        if(firstView) {
            firstView = false;
            segments.put("start", "1");
        }
        recordEvent("[CLY]_view", segments, 1);
        return this;
    }

    /**
     * Sets information about user. Possible keys are:
     * <ul>
     * <li>
     * name - (String) providing user's full name
     * </li>
     * <li>
     * username - (String) providing user's nickname
     * </li>
     * <li>
     * email - (String) providing user's email address
     * </li>
     * <li>
     * organization - (String) providing user's organization's name where user works
     * </li>
     * <li>
     * phone - (String) providing user's phone number
     * </li>
     * <li>
     * picture - (String) providing WWW URL to user's avatar or profile picture
     * </li>
     * <li>
     * picturePath - (String) providing local path to user's avatar or profile picture
     * </li>
     * <li>
     * gender - (String) providing user's gender as M for male and F for female
     * </li>
     * <li>
     * byear - (int) providing user's year of birth as integer
     * </li>
     * </ul>
     * @param data Map&lt;String, String&gt; with user data
     * @deprecated use {@link #Countly().sharedInstance().userData.setUserData(Map<String, String>)} to set data and {@link #Countly().sharedInstance().userData.save()} to send it to server.
     */
    public synchronized Countly setUserData(Map<String, String> data) {
        return setUserData(data, null);
    }

    /**
     * Sets information about user with custom properties.
     * In custom properties you can provide any string key values to be stored with user
     * Possible keys are:
     * <ul>
     * <li>
     * name - (String) providing user's full name
     * </li>
     * <li>
     * username - (String) providing user's nickname
     * </li>
     * <li>
     * email - (String) providing user's email address
     * </li>
     * <li>
     * organization - (String) providing user's organization's name where user works
     * </li>
     * <li>
     * phone - (String) providing user's phone number
     * </li>
     * <li>
     * picture - (String) providing WWW URL to user's avatar or profile picture
     * </li>
     * <li>
     * picturePath - (String) providing local path to user's avatar or profile picture
     * </li>
     * <li>
     * gender - (String) providing user's gender as M for male and F for female
     * </li>
     * <li>
     * byear - (int) providing user's year of birth as integer
     * </li>
     * </ul>
     * @param data Map&lt;String, String&gt; with user data
     * @param customdata Map&lt;String, String&gt; with custom key values for this user
     * @deprecated use {@link #Countly().sharedInstance().userData.setUserData(Map<String, String>, Map<String, String>)} to set data and {@link #Countly().sharedInstance().userData.save()} to send it to server.
     */
    public synchronized Countly setUserData(Map<String, String> data, Map<String, String> customdata) {
        UserData.setData(data);
        if(customdata != null)
            UserData.setCustomData(customdata);
        connectionQueue_.sendUserData();
        UserData.clear();
        return this;
    }

    /**
     * Sets custom properties.
     * In custom properties you can provide any string key values to be stored with user
     * @param customdata Map&lt;String, String&gt; with custom key values for this user
     * @deprecated use {@link #Countly().sharedInstance().userData.setCustomUserData(Map<String, String>)} to set data and {@link #Countly().sharedInstance().userData.save()} to send it to server.
     */
    public synchronized Countly setCustomUserData(Map<String, String> customdata) {
        if(customdata != null)
            UserData.setCustomData(customdata);
        connectionQueue_.sendUserData();
        UserData.clear();
        return this;
    }

    /**
     * Set user location.
     *
     * Countly detects user location based on IP address. But for geolocation-enabled apps,
     * it's better to supply exact location of user.
     * Allows sending messages to a custom segment of users located in a particular area.
     *
     * @param lat Latitude
     * @param lon Longitude
     */
    public synchronized Countly setLocation(double lat, double lon) {
        connectionQueue_.getCountlyStore().setLocation(lat, lon);

        if (disableUpdateSessionRequests_) {
            connectionQueue_.updateSession(roundedSecondsSinceLastSessionDurationUpdate());
        }

        return this;
    }

    /**
     * Sets custom segments to be reported with crash reports
     * In custom segments you can provide any string key values to segments crashes by
     * @param segments Map&lt;String, String&gt; key segments and their values
     */
    public synchronized Countly setCustomCrashSegments(Map<String, String> segments) {
        if(segments != null)
            CrashDetails.setCustomSegments(segments);
        return this;
    }

    /**
     * 添加一个崩溃痕迹（日志记录的形式），添加到日志中（这些日志被存在数组中，等待与崩溃报告一起发送）
     *
     * Add crash breadcrumb like log record to the log that will be send together with crash report
     * @param record String a bread crumb for the crash report
     */
    public synchronized Countly addCrashLog(String record) {
        CrashDetails.addLog(record);
        return this;
    }

    /**
     * 记录已经发生的异常，监控异常发生的形式和异常
     *
     * Log handled exception to report it to server as non fatal crash
     * @param exception Exception to log
     */
    public synchronized Countly logException(Exception exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        connectionQueue_.sendCrashReport(sw.toString(), true);
        return this;
    }

    /**
     *
     * 设置UncaughtExceptionHandler ,用于给Thread设置在Thread意外终止时要做的事情（由于线程的run方法本身不会抛出任何checked异常）
     *
     * Enable crash reporting to send unhandled crash reports to server
     */
    public synchronized Countly enableCrashReporting() {
        //get default handler
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                Countly.sharedInstance().connectionQueue_.sendCrashReport(sw.toString(), false);

                //if there was another handler before
                if(oldHandler != null){
                    //notify it also
                    oldHandler.uncaughtException(t,e);
                }
            }
        };

        Thread.setDefaultUncaughtExceptionHandler(handler);
        return this;
    }

    /**
     * Disable periodic session time updates.
     * By default, Countly will send a request to the server each 30 seconds with a small update
     * containing session duration time. This method allows you to disable such behavior.
     * Note that event updates will still be sent every 10 events or 30 seconds after event recording.
     * @param disable whether or not to disable session time updates
     * @return Countly instance for easy method chaining
     */
    public synchronized Countly setDisableUpdateSessionRequests(final boolean disable) {
        disableUpdateSessionRequests_ = disable;
        return this;
    }

    /**
     * 设置是否在debug时进行log的显示。默认是不显示
     *
     * Sets whether debug logging is turned on or off. Logging is disabled by default.
     * @param enableLogging true to enable logging, false to disable logging
     * @return Countly instance for easy method chaining
     */
    public synchronized Countly setLoggingEnabled(final boolean enableLogging) {
        enableLogging_ = enableLogging;
        return this;
    }

    public synchronized boolean isLoggingEnabled() {
        return enableLogging_;
    }

    private boolean appLaunchDeepLink = true;


    /**
     * 首先，获取Activity对应的启动项Intent对象（实际上是获取这个Activity的一些属性信息）
     * 如果能够输出log，就输出“Activity 被创建”
     * @param activity
     */
    public static void onCreate(Activity activity) {
        /*实际是activity的Context的实现类：ContextWrapper.getPackageManager()获取到PackageManager对象
        * PackageManager:
        * 1、安装，卸载应用
        2、查询permission相关信息
        3、查询Application相关信息(application，activity，receiver，service，provider及相应属性等）
        4、查询已安装应用
        5、增加，删除permission
        6、清除用户数据、缓存，代码段等
        *
        *
        * 最终:if application exists main(ru kou) intent , then return this intent
        * */

        Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());

        if (sharedInstance().isLoggingEnabled()) {
            Log.d(Countly.TAG, "Activity created: " + activity.getClass().getName() + " ( main is " + launchIntent.getComponent().getClassName() + ")");
        }

        Intent intent = activity.getIntent();
        if (intent != null) {
            Uri data = intent.getData();
            if (data != null) {
                if (sharedInstance().isLoggingEnabled()) {
                    Log.d(Countly.TAG, "Data in activity created intent: " + data + " (appLaunchDeepLink " + sharedInstance().appLaunchDeepLink + ") " );
                }
                if (sharedInstance().appLaunchDeepLink) {
                    DeviceInfo.deepLink = data.toString();
                }
            }
        }
    }

    /**
     * 发送最后一个view的持续时间给后台
     * Reports duration of last view
     */
    void reportViewDuration(){
        if(lastView != null){
            HashMap<String, String> segments = new HashMap<String, String>();
            segments.put("name", lastView);
            segments.put("dur", String.valueOf(Countly.currentTimestamp()-lastViewStart));
            segments.put("segment", "Android");
            recordEvent("[CLY]_view",segments,1);
            lastView = null;
            lastViewStart = 0;
        }
    }

    /**
     * 如果本地的事件队列已经超过了10个事件，那么就提交所有的本地事件
     * Submits all of the locally queued events to the server if there are more than 10 of them.
     */
    void sendEventsIfNeeded() {
        if (eventQueue_.size() >= EVENT_QUEUE_SIZE_THRESHOLD) {
            connectionQueue_.recordEvents(eventQueue_.events());
        }
    }

    /**
     * Called every 60 seconds to send a session heartbeat to the server. Does nothing if there
     * is not an active application session.
     */
    synchronized void onTimer() {
        final boolean hasActiveSession = activityCount_ > 0;
        if (hasActiveSession) {
            if (!disableUpdateSessionRequests_) {
                connectionQueue_.updateSession(roundedSecondsSinceLastSessionDurationUpdate());
            }
            if (eventQueue_.size() > 0) {
                connectionQueue_.recordEvents(eventQueue_.events());
            }
        }
    }

    /**
     * Calculates the unsent session duration in seconds, rounded to the nearest int.
     */
    int roundedSecondsSinceLastSessionDurationUpdate() {
        final long currentTimestampInNanoseconds = System.nanoTime();
        final long unsentSessionLengthInNanoseconds = currentTimestampInNanoseconds - prevSessionDurationStartTime_;
        prevSessionDurationStartTime_ = currentTimestampInNanoseconds;
        return (int) Math.round(unsentSessionLengthInNanoseconds / 1000000000.0d);
    }

    /**
     * Utility method to return a current timestamp that can be used in the Count.ly API.
     */
    static int currentTimestamp() {
        return ((int)(System.currentTimeMillis() / 1000l));
    }

    /**
     * Utility method to return a current hour of the day that can be used in the Count.ly API.
     */
    static int currentHour(){return Calendar.getInstance().get(Calendar.HOUR_OF_DAY); }

    /**
     * Utility method to return a current day of the week that can be used in the Count.ly API.
     */
    static int currentDayOfWeek(){
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        switch (day) {
            case Calendar.MONDAY:
                return 1;
            case Calendar.TUESDAY:
                return 2;
            case Calendar.WEDNESDAY:
                return 3;
            case Calendar.THURSDAY:
                return 4;
            case Calendar.FRIDAY:
                return 5;
            case Calendar.SATURDAY:
                return 6;
        }
        return 0;
    }

    /**
     * Utility method for testing validity of a URL.
     */
    static boolean isValidURL(final String urlStr) {
        boolean validURL = false;
        if (urlStr != null && urlStr.length() > 0) {
            try {
                new URL(urlStr);
                validURL = true;
            }
            catch (MalformedURLException e) {
                validURL = false;
            }
        }
        return validURL;
    }

    /**
     * Allows public key pinning.
     * Supply list of SSL certificates (base64-encoded strings between "-----BEGIN CERTIFICATE-----" and "-----END CERTIFICATE-----" without end-of-line)
     * along with server URL starting with "https://". Countly will only accept connections to the server
     * if public key of SSL certificate provided by the server matches one provided to this method.
     * @param certificates List of SSL certificates
     * @return Countly instance
     */
    public static Countly enablePublicKeyPinning(List<String> certificates) {
        publicKeyPinCertificates = certificates;
        return Countly.sharedInstance();
    }

    // for unit testing
    ConnectionQueue getConnectionQueue() { return connectionQueue_; }
    void setConnectionQueue(final ConnectionQueue connectionQueue) { connectionQueue_ = connectionQueue; }
    ExecutorService getTimerService() { return timerService_; }
    EventQueue getEventQueue() { return eventQueue_; }
    void setEventQueue(final EventQueue eventQueue) { eventQueue_ = eventQueue; }
    long getPrevSessionDurationStartTime() { return prevSessionDurationStartTime_; }
    void setPrevSessionDurationStartTime(final long prevSessionDurationStartTime) { prevSessionDurationStartTime_ = prevSessionDurationStartTime; }
    int getActivityCount() { return activityCount_; }
    synchronized boolean getDisableUpdateSessionRequests() { return disableUpdateSessionRequests_; }

    public void stackOverflow() {
        this.stackOverflow();
    }

    public synchronized Countly crashTest(int crashNumber) {

        if (crashNumber == 1){
            if (Countly.sharedInstance().isLoggingEnabled()) {
                Log.d(Countly.TAG, "Running crashTest 1");
            }

            stackOverflow();

        }else if (crashNumber == 2){

            if (Countly.sharedInstance().isLoggingEnabled()) {
                Log.d(Countly.TAG, "Running crashTest 2");
            }

            int test = 10/0;

        }else if (crashNumber == 3){

            if (Countly.sharedInstance().isLoggingEnabled()) {
                Log.d(Countly.TAG, "Running crashTest 3");
            }

            Object[] o = null;
            while (true) { o = new Object[] { o }; }


        }else if (crashNumber == 4){

            if (Countly.sharedInstance().isLoggingEnabled()) {
                Log.d(Countly.TAG, "Running crashTest 4");
            }

            throw new RuntimeException("This is a crash");
        }
        else{
            if (Countly.sharedInstance().isLoggingEnabled()) {
                Log.d(Countly.TAG, "Running crashTest 5");
            }

            String test = null;
            test.charAt(1);
        }
        return Countly.sharedInstance();
    }
}
