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

import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * 请求处理类（实现了Runnable接口）
 *
 * ConnectionProcessor is a Runnable that is executed on a background
 * thread to submit session &amp; event data to a Count.ly server.
 *
 * NOTE: This class is only public to facilitate unit testing, because
 *       of this bug in dexmaker: https://code.google.com/p/dexmaker/issues/detail?id=34
 */
public class ConnectionProcessor implements Runnable {

    /**
     * 连接超时：30s
     * 读取超时：30s
     *
     * 引用：CountlyStore、DeviceId、SSLContext
     *
     */
    private static final int CONNECT_TIMEOUT_IN_MILLISECONDS = 30000;
    private static final int READ_TIMEOUT_IN_MILLISECONDS = 30000;

    private final CountlyStore store_;
    private final DeviceId deviceId_;
    private final String serverURL_;
    private final SSLContext sslContext_;

    ConnectionProcessor(final String serverURL, final CountlyStore store, final DeviceId deviceId, final SSLContext sslContext) {
        serverURL_ = serverURL;
        store_ = store;
        deviceId_ = deviceId;
        sslContext_ = sslContext;

        // HTTP connection reuse which was buggy pre-froyo
        /**
         * 在Android 2.2.x以前，需要配置系统属性
         */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    /**
     * 将string形式的参数串，转换为 URLConnection
     *
     * 1.构造完整URL串（参数中没有“&crash=”时，加上参数串）
     * 2.String --> URL
     * 3.构造HttpURLConnection
     * 4.如果公钥数字证书为null，直接打开连接，开始发送请求；证书不为null，还可以在connection对象上加上安全套接字协议
     * 5.设置HttpURLConnection的配置（setDoInput(true)等）
     * 6.有用户图片提交，就得将图片文件内容写入URLConnection内部
     * 7.否则，如果有“crash”属性，那么，需要Post方式请求，同样写在内容中
     * 8.返回URLConnection对象
     *
     * @param eventData
     * @return
     * @throws IOException
     */
    URLConnection urlConnectionForEventData(final String eventData) throws IOException {
        String urlStr = serverURL_ + "/i?";
        if(!eventData.contains("&crash="))
            urlStr += eventData;
        final URL url = new URL(urlStr);
        final HttpURLConnection conn;
        if (Countly.publicKeyPinCertificates == null) {
            conn = (HttpURLConnection)url.openConnection();
        } else {
            HttpsURLConnection c = (HttpsURLConnection)url.openConnection();
            c.setSSLSocketFactory(sslContext_.getSocketFactory());
            conn = c;
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_IN_MILLISECONDS);
        conn.setReadTimeout(READ_TIMEOUT_IN_MILLISECONDS);
        conn.setUseCaches(false);
        conn.setDoInput(true);/*可以在里面读取数据出来*/

        /*获取图片路径(根据picturePath参数)*/
        String picturePath = UserData.getPicturePathFromQuery(url);

        if (Countly.sharedInstance().isLoggingEnabled()) {
            Log.d(Countly.TAG, "Got picturePath: " + picturePath);
        }

        /*如果图片路径存在*/
        if(!picturePath.equals("")){
        	//Uploading files:
        	//http://stackoverflow.com/questions/2793150/how-to-use-java-net-urlconnection-to-fire-and-handle-http-requests
        	
        	File binaryFile = new File(picturePath);
        	conn.setDoOutput(true);/*可以添加数据到conn中*/
        	// Just generate some unique random value.
        	String boundary = Long.toHexString(System.currentTimeMillis());

            /**
             * 设置了可以向conn对象写入数据后，
             * 行分隔符分隔开各个数据:
             * 以当前时刻（long转 HexString）作为内容的上下边界，然后，内容区逐行添加数据（包括获取图片文件内容并转化为二进制串形式写入）
             *
             */

            // Line separator required by multipart/form-data.
        	String CRLF = "\r\n";
        	String charset = "UTF-8";
        	conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        	OutputStream output = conn.getOutputStream();
        	PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, charset), true);
        	// Send binary file.
            writer.append("--" + boundary).append(CRLF);
            writer.append("Content-Disposition: form-data; name=\"binaryFile\"; filename=\"" + binaryFile.getName() + "\"").append(CRLF);
            writer.append("Content-Type: " + URLConnection.guessContentTypeFromName(binaryFile.getName())).append(CRLF);
            writer.append("Content-Transfer-Encoding: binary").append(CRLF);
            writer.append(CRLF).flush();
            FileInputStream fileInputStream = new FileInputStream(binaryFile);
            byte[] buffer = new byte[1024];
            int len;
            try {
                while ((len = fileInputStream.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                }
            }catch(IOException ex){
                ex.printStackTrace();
            }
            output.flush(); // Important before continuing with writer!
            writer.append(CRLF).flush(); // CRLF is important! It indicates end of boundary.
            fileInputStream.close();

            // End of multipart/form-data.
            writer.append("--" + boundary + "--").append(CRLF).flush();
        }
        else if(eventData.contains("&crash=")){/*如果存在crash这个参数项，就需要用到post的方式，而不是get方式（直接在URL后面补上参数串）*/
            if (Countly.sharedInstance().isLoggingEnabled()) {
                Log.d(Countly.TAG, "Using post because of crash");
            }
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            OutputStream os = conn.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(eventData);
            writer.flush();
            writer.close();
            os.close();
        }
        else{
        	conn.setDoOutput(false);
        }
        return conn;
    }

    @Override
    public void run() {


        /**
         * 死循环：
         * 1：获得所有的请求url String串【如果当前没有请求可发，直接结束循环，退出】
         * 2：【如果deviceId的id不存在，同样直接退出】
         * 3：取首个请求事件集串，加上device_id参数,构成较为完整的eventData串
         * 4：根据这个eventData串，获取URLConnection对象
         * 5：启动连接
         * 6：用 BufferIntputStream 读取响应数据
         * 7：用 ByteArrayOutputStream 将读取的数据以二进制形式写入缓存
         * 8：
         */
        while (true) {
            final String[] storedEvents = store_.connections();
            if (storedEvents == null || storedEvents.length == 0) {
                // currently no data to send, we are done for now
                break;
            }

            // get first event from collection
            if (deviceId_.getId() == null) {
                // When device ID is supplied by OpenUDID or by Google Advertising ID.
                // In some cases it might take time for them to initialize. So, just wait for it.
                if (Countly.sharedInstance().isLoggingEnabled()) {
                    Log.i(Countly.TAG, "No Device ID available yet, skipping request " + storedEvents[0]);
                }
                break;
            }
            final String eventData = storedEvents[0] + "&device_id=" + deviceId_.getId();

            URLConnection conn = null;
            BufferedInputStream responseStream = null;
            try {
                // initialize and open connection
                conn = urlConnectionForEventData(eventData);
                conn.connect();

                // consume response stream
                responseStream = new BufferedInputStream(conn.getInputStream());
                final ByteArrayOutputStream responseData = new ByteArrayOutputStream(256); // big enough to handle success response without reallocating
                int c;
                while ((c = responseStream.read()) != -1) {
                    responseData.write(c);
                }

                /**
                 * 检查响应码是否为success：区间[200,300)
                 */
                // response code has to be 2xx to be considered a success
                boolean success = true;
                if (conn instanceof HttpURLConnection) {
                    final HttpURLConnection httpConn = (HttpURLConnection) conn;
                    final int responseCode = httpConn.getResponseCode();
                    success = responseCode >= 200 && responseCode < 300;
                    if (!success && Countly.sharedInstance().isLoggingEnabled()) {
                        Log.w(Countly.TAG, "HTTP error response code was " + responseCode + " from submitting event data: " + eventData);
                    }
                }

                /**
                 * 同样要检查响应的json串中是否含有{"result":"Success"}
                 */
                // HTTP response code was good, check response JSON contains {"result":"Success"}
                if (success) {
                    final JSONObject responseDict = new JSONObject(responseData.toString("UTF-8"));
                    success = responseDict.optString("result").equalsIgnoreCase("success");
                    if (!success && Countly.sharedInstance().isLoggingEnabled()) {
                        Log.w(Countly.TAG, "Response from Countly server did not report success, it was: " + responseData.toString("UTF-8"));
                    }
                }

                /**
                 * 都成功了，就可以从持久层中删除这一个请求串
                 */
                if (success) {
                    if (Countly.sharedInstance().isLoggingEnabled()) {
                        Log.d(Countly.TAG, "ok ->" + eventData);
                    }

                    // successfully submitted event data to Count.ly server, so remove
                    // this one from the stored events collection
                    store_.removeConnection(storedEvents[0]);
                }
                else {
                    // warning was logged above, stop processing, let next tick take care of retrying
                    break;
                }
            }
            catch (Exception e) {
                if (Countly.sharedInstance().isLoggingEnabled()) {
                    Log.w(Countly.TAG, "Got exception while trying to submit event data: " + eventData, e);
                }
                // if exception occurred, stop processing, let next tick take care of retrying
                break;
            }
            finally {
                /**
                 * 最后，释放资源，断开连接
                 */
                // free connection resources
                if (responseStream != null) {
                    try { responseStream.close(); } catch (IOException ignored) {}
                }
                if (conn != null && conn instanceof HttpURLConnection) {
                    ((HttpURLConnection)conn).disconnect();
                }
            }
        }
    }

    // for unit testing
    String getServerURL() { return serverURL_; }
    CountlyStore getCountlyStore() { return store_; }
    DeviceId getDeviceId() { return deviceId_; }
}
