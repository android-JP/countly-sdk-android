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

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 *
 * 请求队列 ：
 * 管理着 会话 和 事件
 * 周期性地上传数据到服务器【在后台线程中】
 *
 * 成员：CountlyStore（存储类）、ExecutorService（线程池）、各个主要配置参数（appKey等）、Future（请求控制对象）、DeviceID（设备ID）、SSLContext
 *
 * ConnectionQueue queues session and event data and periodically sends that data to
 * a Count.ly server on a background thread.
 *
 * None of the methods in this class are synchronized because access to this class is
 * controlled by the Countly singleton, which is synchronized.
 *
 * NOTE: This class is only public to facilitate unit testing, because
 *       of this bug in dexmaker: https://code.google.com/p/dexmaker/issues/detail?id=34
 */
public class ConnectionQueue {
    private CountlyStore store_;
    private ExecutorService executor_;
    private String appKey_;
    private Context context_;
    private String serverURL_;
    private Future<?> connectionProcessorFuture_;
    private DeviceId deviceId_;
    private SSLContext sslContext_;//安全套接字协议

    // Getters are for unit testing
    String getAppKey() {
        return appKey_;
    }

    void setAppKey(final String appKey) {
        appKey_ = appKey;
    }

    Context getContext() {
        return context_;
    }

    void setContext(final Context context) {
        context_ = context;
    }

    String getServerURL() {
        return serverURL_;
    }

    void setServerURL(final String serverURL) {
        serverURL_ = serverURL;
        if (Countly.publicKeyPinCertificates == null) {
            sslContext_ = null;
        } else {
            try {
                TrustManager tm[] = { new CertificateTrustManager(Countly.publicKeyPinCertificates) };
                sslContext_ = SSLContext.getInstance("TLS");
                sslContext_.init(null, tm, null);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }

    CountlyStore getCountlyStore() {
        return store_;
    }

    void setCountlyStore(final CountlyStore countlyStore) {
        store_ = countlyStore;
    }

    DeviceId getDeviceId() { return deviceId_; }

    public void setDeviceId(DeviceId deviceId) {
        this.deviceId_ = deviceId;
    }

    /**
     * 检查内部变量状态是否可用（context、appKey、countlyStore对象、serverURL 均不为空，并且，要么公钥证书列表为null，要么必须serverURL以“https”开头）
     * Checks internal state and throws IllegalStateException if state is invalid to begin use.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void checkInternalState() {
        if (context_ == null) {
            throw new IllegalStateException("context has not been set");
        }
        if (appKey_ == null || appKey_.length() == 0) {
            throw new IllegalStateException("app key has not been set");
        }
        if (store_ == null) {
            throw new IllegalStateException("countly store has not been set");
        }
        if (serverURL_ == null || !Countly.isValidURL(serverURL_)) {
            throw new IllegalStateException("server URL is not valid");
        }
        if (Countly.publicKeyPinCertificates != null && !serverURL_.startsWith("https")) {
            throw new IllegalStateException("server must start with https once you specified public keys");
        }
    }

    /**
     * 开始会话：为app记录一个会话开始事件，并将其提交到服务器
     *
     * 1：构建 请求参数url后缀
     * 2：永久保存一个请求连接到spf中
     * 3：确保单例线程池存在，并新建一个请求处理类，提交到线程池中处理请求
     *
     * Records a session start event for the app and sends it to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void beginSession() {
        checkInternalState();
        final String data = "app_key=" + appKey_
                          + "&timestamp=" + Countly.currentTimestamp()
                          + "&hour=" + Countly.currentHour()
                          + "&dow=" + Countly.currentDayOfWeek()
                          + "&sdk_version=" + Countly.COUNTLY_SDK_VERSION_STRING
                          + "&begin_session=1"
                          + "&metrics=" + DeviceInfo.getMetrics(context_);

        store_.addConnection(data);

        tick();
    }

    /**
     * Records a session duration event for the app and sends it to the server. This method does nothing
     * if passed a negative or zero duration.
     * @param duration duration in seconds to extend the current app session, should be more than zero
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void updateSession(final int duration) {
        checkInternalState();
        if (duration > 0) {
            final String data = "app_key=" + appKey_
                              + "&timestamp=" + Countly.currentTimestamp()
                              + "&hour=" + Countly.currentHour()
                              + "&dow=" + Countly.currentDayOfWeek()
                              + "&session_duration=" + duration
                              + "&location=" + getCountlyStore().getAndRemoveLocation();

            store_.addConnection(data);

            tick();
        }
    }

    public void tokenSession(String token, Countly.CountlyMessagingMode mode) {
        checkInternalState();

        final String data = "app_key=" + appKey_
                + "&" + "timestamp=" + Countly.currentTimestamp()
                + "&hour=" + Countly.currentHour()
                + "&dow=" + Countly.currentDayOfWeek()
                + "&" + "token_session=1"
                + "&" + "android_token=" + token
                + "&" + "test_mode=" + (mode == Countly.CountlyMessagingMode.TEST ? 2 : 0)
                + "&" + "locale=" + DeviceInfo.getLocale();

        // To ensure begin_session will be fully processed by the server before token_session
        final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        worker.schedule(new Runnable() {
            @Override
            public void run() {
                store_.addConnection(data);
                tick();
            }
        }, 10, TimeUnit.SECONDS);
    }

    /**
     * 记录一个会话终止事件，并发送它到服务器
     *
     * 持续时间仅仅被包含在 会话终止事件中（如果duration不为0）
     *
     * Records a session end event for the app and sends it to the server. Duration is only included in
     * the session end event if it is more than zero.
     * @param duration duration in seconds to extend the current app session
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void endSession(final int duration) {
        checkInternalState();
        String data = "app_key=" + appKey_
                    + "&timestamp=" + Countly.currentTimestamp()
                    + "&hour=" + Countly.currentHour()
                    + "&dow=" + Countly.currentDayOfWeek()
                    + "&end_session=1";
        if (duration > 0) {
            data += "&session_duration=" + duration;
        }

        store_.addConnection(data);

        tick();
    }

    /**
     * 请求队列 将User的信息整合，给后台线程上传到服务端
     *
     * 首先：把User的配置属性整合（先把各个基本的属性构造成一个json串）
     * （1：如果这个json串存在，就加上这个json串）
     * （2：如果有picturePath这一项存在，就让URLEncoder去将这个路径下的图片解析为图片url，然后加上）
     * 最终得到一个长String串，作为总的参数
     *
     * 然后：将这个长串（userdata）加到总串上，作为一个请求的总参数串，最终保存在spf文件中
     *
     * 最后：让后台线程去执行这个请求任务
     *
     *
     *
     * Send user data to the server.
     * @throws java.lang.IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendUserData() {
        checkInternalState();
        String userdata = UserData.getDataForRequest();

        if(!userdata.equals("")){
            String data = "app_key=" + appKey_
                    + "&timestamp=" + Countly.currentTimestamp()
                    + "&hour=" + Countly.currentHour()
                    + "&dow=" + Countly.currentDayOfWeek()
                    + userdata;
            store_.addConnection(data);

            tick();
        }
    }

    /**
     * Attribute installation to Countly server.
     * @param referrer query parameters
     * @throws java.lang.IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendReferrerData(String referrer) {
        checkInternalState();

        if(referrer != null){
            String data = "app_key=" + appKey_
                    + "&timestamp=" + Countly.currentTimestamp()
                    + "&hour=" + Countly.currentHour()
                    + "&dow=" + Countly.currentDayOfWeek()
                    + referrer;
            store_.addConnection(data);

            tick();
        }
    }

    /**
     * Reports a crash with device data to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendCrashReport(String error, boolean nonfatal) {
        checkInternalState();
        final String data = "app_key=" + appKey_
                + "&timestamp=" + Countly.currentTimestamp()
                + "&hour=" + Countly.currentHour()
                + "&dow=" + Countly.currentDayOfWeek()
                + "&sdk_version=" + Countly.COUNTLY_SDK_VERSION_STRING
                + "&crash=" + CrashDetails.getCrashData(context_, error, nonfatal);

        store_.addConnection(data);

        tick();
    }

    /**
     * 在请求队列中记录【事件集（JsonArray 形式）】
     * 1.首先，检查了内部数据格式是否正确
     * 2.构造URL请求参数（其中，appKey_在Countly.init()中，就设置了）
     * 3.永久存储这个请求串
     * 4.开启后台线程，让请求处理器去处理请求
     *
     * Records the specified events and sends them to the server.
     * @param events URL-encoded JSON string of event data
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void recordEvents(final String events) {
        checkInternalState();
        final String data = "app_key=" + appKey_
                          + "&timestamp=" + Countly.currentTimestamp()
                          + "&hour=" + Countly.currentHour()
                          + "&dow=" + Countly.currentDayOfWeek()
                          + "&events=" + events;


        /*CountlyStore 中永久添加一个请求(存储在spf文件中) 内部： spf文件 --> String --> String[] --> List<String> -->添加data --> commit提交  */
        store_.addConnection(data);

        /*
        开启ConnectionProcessor去后台线程处理请求（发送请求到服务器）
        * */
        tick();
    }

    /**
     * Records the specified events and sends them to the server.
     * @param events URL-encoded JSON string of event data
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void recordLocation(final String events) {
        checkInternalState();
        final String data = "app_key=" + appKey_
                          + "&timestamp=" + Countly.currentTimestamp()
                          + "&hour=" + Countly.currentHour()
                          + "&dow=" + Countly.currentDayOfWeek()
                          + "&events=" + events;

        store_.addConnection(data);

        tick();
    }

    /**
     * 保证线程池对象已经创建（可以被ConnectionProcessor实例提交请求了）
     * 【单线程模式的线程池】
     * Ensures that an executor has been created for ConnectionProcessor instances to be submitted to.
     */
    void ensureExecutor() {
        if (executor_ == null) {
            executor_ = Executors.newSingleThreadExecutor();
            ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newSingleThreadExecutor();
        }
    }

    /**
     * 开启ConnectionProcessor实例来在后台跑，用于处理【本地的请求队列数据】
     *
     *
     *
     * 如果CountlyStore 还有请求没有处理，并且，ConnectionProcessor没有初始化 或者 它执行完了一个请求操作（现在是空闲的）
     * ，那么，就发送新的的请求：首先确保线程池初始化了，然后，让单例线程池去提交请求，并获取这个请求的控制类Future对象
     *
     *
     * Starts ConnectionProcessor instances running in the background to
     * process the local connection queue data.
     * Does nothing if there is connection queue data or if a ConnectionProcessor
     * is already running.
     */
    void tick() {
        if (!store_.isEmptyConnections() && (connectionProcessorFuture_ == null || connectionProcessorFuture_.isDone())) {
            ensureExecutor();
            connectionProcessorFuture_ = executor_.submit(new ConnectionProcessor(serverURL_, store_, deviceId_, sslContext_));
        }
    }

    // for unit testing
    ExecutorService getExecutor() { return executor_; }
    void setExecutor(final ExecutorService executor) { executor_ = executor; }
    Future<?> getConnectionProcessorFuture() { return connectionProcessorFuture_; }
    void setConnectionProcessorFuture(final Future<?> connectionProcessorFuture) { connectionProcessorFuture_ = connectionProcessorFuture; }

}
