package com.ucloud.library.netanalysis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.ucloud.library.netanalysis.api.bean.IpInfoBean;
import com.ucloud.library.netanalysis.api.bean.IpListBean;
import com.ucloud.library.netanalysis.api.bean.MessageBean;
import com.ucloud.library.netanalysis.api.bean.PingDataBean;
import com.ucloud.library.netanalysis.api.bean.PingDomainResult;
import com.ucloud.library.netanalysis.api.bean.PublicIpBean;
import com.ucloud.library.netanalysis.api.bean.TracerouteDataBean;
import com.ucloud.library.netanalysis.api.bean.UCApiResponseBean;
import com.ucloud.library.netanalysis.api.http.Response;
import com.ucloud.library.netanalysis.callback.OnAnalyseListener;
import com.ucloud.library.netanalysis.callback.OnSdkListener;
import com.ucloud.library.netanalysis.command.bean.UCommandStatus;
import com.ucloud.library.netanalysis.command.net.ping.Ping;
import com.ucloud.library.netanalysis.command.net.ping.PingCallback;
import com.ucloud.library.netanalysis.command.net.ping.PingResult;
import com.ucloud.library.netanalysis.command.net.traceroute.Traceroute;
import com.ucloud.library.netanalysis.command.net.traceroute.TracerouteCallback;
import com.ucloud.library.netanalysis.command.net.traceroute.TracerouteNodeResult;
import com.ucloud.library.netanalysis.command.net.traceroute.TracerouteResult;
import com.ucloud.library.netanalysis.exception.UCHttpException;
import com.ucloud.library.netanalysis.module.IpReport;
import com.ucloud.library.netanalysis.module.UserDefinedData;
import com.ucloud.library.netanalysis.module.UCAnalysisResult;
import com.ucloud.library.netanalysis.module.UCNetStatus;
import com.ucloud.library.netanalysis.module.UCNetworkInfo;
import com.ucloud.library.netanalysis.module.UCSdkStatus;
import com.ucloud.library.netanalysis.utils.UCConfig;
import com.ucloud.library.netanalysis.utils.Encryptor;
import com.ucloud.library.netanalysis.utils.JLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by joshua on 2018/8/29 18:42.
 * Company: UCloud
 * E-mail: joshua.yin@ucloud.cn
 */
public class UCNetAnalysisManager {
    private final String TAG = getClass().getSimpleName();
    
    public static final String SDK_VERSION = String.format("Android/%s", BuildConfig.VERSION_NAME);
    public static final int CUSTOM_IP_LIST_SIZE = 5;
    
    private static volatile UCNetAnalysisManager mInstance = null;
    private UCConfig config;
    private UCApiManager mApiManager;
    private Context mContext;
    
    private ExecutorService mCmdThreadPool;
    private UNetStatusReceiver mNetStatusReceiver;
    private boolean isStartMonitorNetStatus = false;
    private TelephonyManager mTelephonyManager;
    private SignalStrength mMobileSignalStrength;
    
    private OnSdkListener mSdkListener;
    
    private IpListBean mIpListCache;
    private List<String> mReportAddr;
    private List<String> mCustomIps, mCustomIpsCache;
    private IpInfoBean mCurSrcIpInfo;
    private ReentrantLock mCacheLock, mCustomLock;
    
    private String appSecret;
    private String appKey;
    private UserDefinedData userDefinedData;
    
    private String mDomain;
    private PingDomainResult mDomainResult;
    
    private UCNetAnalysisManager(Context context, String appKey, String appSecret, UCConfig config) {
        this.mContext = context;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.mApiManager = UCApiManager.create(mContext, appKey, appSecret);
        this.mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        this.mCacheLock = new ReentrantLock();
        this.mCustomLock = new ReentrantLock();
        this.mCustomIps = new ArrayList<>();
        this.config = config == null ? new UCConfig() : config;
        this.config.handleConfig();
    }
    
    public synchronized static UCNetAnalysisManager createManager(@NonNull Context applicationContext,
                                                                  @NonNull String appKey, @NonNull String appSecret, UCConfig config) {
        if (TextUtils.isEmpty(appKey))
            throw new IllegalArgumentException("appKey is empty!");
        if (TextUtils.isEmpty(appSecret))
            throw new IllegalArgumentException("appSecret is empty!");
        appSecret = Encryptor.filterRsaKey(appSecret);
        if (TextUtils.isEmpty(appSecret))
            throw new IllegalArgumentException("appSecret is illegal!");
        
        synchronized (UCNetAnalysisManager.class) {
            destroy();
            mInstance = new UCNetAnalysisManager(applicationContext, appKey, appSecret, config);
        }
        
        return mInstance;
    }
    
    public synchronized static UCNetAnalysisManager createManager(@NonNull Context applicationContext,
                                                                  @NonNull String appKey, @NonNull String appSecret) {
        return createManager(applicationContext, appKey, appSecret, new UCConfig());
    }
    
    public static UCNetAnalysisManager getManager() {
        return mInstance;
    }
    
    public static void destroy() {
        if (mInstance != null) {
            mInstance.destroyObj();
        }
        
        mInstance = null;
    }
    
    private void destroyObj() {
        if (mCmdThreadPool != null && !mCmdThreadPool.isShutdown())
            mCmdThreadPool.shutdownNow();
        
        clearIpList();
        stopMonitorNetStatus();
        System.gc();
    }
    
    public void setSdkListener(OnSdkListener listener) {
        mSdkListener = listener;
    }
    
    public void register(OnSdkListener listener) {
        register(listener, null);
    }
    
    public void register(OnSdkListener listener, UserDefinedData userDefinedData) {
        setSdkListener(listener);
        
        if (TextUtils.isEmpty(appKey) || TextUtils.isEmpty(appSecret)) {
            if (mSdkListener != null)
                mSdkListener.onRegister(UCSdkStatus.APPKEY_OR_APPSECRET_ILLEGAL);
            
            return;
        }
        
        this.userDefinedData = userDefinedData;
        
        startMonitorNetStatus();
        if (mSdkListener != null)
            mSdkListener.onRegister(UCSdkStatus.REGISTER_SUCCESS);
    }
    
    public void setCustomIps(List<String> ips) {
        mCustomIpsCache = ips;
        new Thread() {
            @Override
            public void run() {
                JLog.T(TAG, "run thread:" + getId() + " name:" + getName());
                mCustomLock.lock();
                mCustomIps.clear();
                if (mCustomIpsCache != null && !mCustomIpsCache.isEmpty()) {
                    if (mCustomIpsCache.size() > CUSTOM_IP_LIST_SIZE) {
                        for (int i = 0; i < CUSTOM_IP_LIST_SIZE; i++)
                            mCustomIps.add(mCustomIpsCache.get(i));
                    } else {
                        mCustomIps.addAll(mCustomIpsCache);
                    }
                }
                mCustomLock.unlock();
                System.gc();
                
                if (!isStartMonitorNetStatus)
                    return;
                
                if (mDomainResult == null)
                    checkDomain();
                enqueueCustom();
            }
        }.start();
    }
    
    public List<String> getCustomIps() {
        return mCustomIpsCache;
    }
    
    private boolean isCustomAnalysing = false;
    
    private OnAnalyseListener mCustomAnalyseListener = null;
    
    public void analyse(OnAnalyseListener listener) {
        if (isCustomAnalysing)
            return;
        
        isCustomAnalysing = true;
        mCustomAnalyseListener = listener;
        // 如果手动检测触发，则强制关闭自动检测
        if (mCmdThreadPool != null)
            mCmdThreadPool.shutdownNow();
        
        System.gc();
        mCmdThreadPool = Executors.newSingleThreadExecutor();
        mCmdThreadPool.execute(new CustomAnalyseRunner(mCustomAnalyseListener));
    }
    
    private class CustomAnalyseRunner implements Runnable {
        private OnAnalyseListener listener;
        private List<IpReport> reports;
        
        public CustomAnalyseRunner(OnAnalyseListener listener) {
            this.listener = listener;
            reports = new ArrayList<>();
        }
        
        @Override
        public void run() {
            JLog.T(TAG, "run thread:" + Thread.currentThread().getId() + " name:" + Thread.currentThread().getName());
            mCustomLock.lock();
            if (mCustomIps == null || mCustomIps.isEmpty()) {
                JLog.W(TAG, "Your custom IP list is empty! Please make sure you have executed 'UCNetAnalysisManager.setCustomIps(List)' first.");
                UCAnalysisResult analysisResult = new UCAnalysisResult();
                analysisResult.setIpReports(reports);
                if (listener != null)
                    listener.onAnalysed(analysisResult);
                mCustomLock.unlock();
                return;
            }
            
            List<String> customIps = new ArrayList<>();
            customIps.addAll(mCustomIps);
            mCustomLock.unlock();
            
            final int size = customIps.size();
            
            PingCallback pingCallback = new PingCallback() {
                @Override
                public void onPingFinish(PingResult result, UCommandStatus status) {
                    JLog.D(TAG, String.format("[status]:%s [res]:%s", status.name(), result == null ? "null" : result.toString()));
                    if (result != null && status == UCommandStatus.CMD_STATUS_SUCCESSFUL)
                        reportCustomIpPing(result, mReportAddr);
                    
                    IpReport report = new IpReport();
                    if (result != null) {
                        report.setIp(result.getTargetIp());
                        report.setAverageDelay(result.averageDelay());
                        report.setPackageLossRate(result.lossRate());
                    } else {
                        report.setIp(status.name());
                        report.setAverageDelay(-1);
                        report.setPackageLossRate(-1);
                    }
                    
                    report.setNetStatus(checkNetworkStatus().getNetStatus());
                    reports.add(report);
                    
                    int count = reports.size();
                    if (count == size) {
                        UCAnalysisResult analysisResult = new UCAnalysisResult();
                        analysisResult.setIpReports(reports);
                        if (listener != null)
                            listener.onAnalysed(analysisResult);
                        
                        isCustomAnalysing = false;
                    }
                }
            };
            
            checkDomain();
            
            for (String ip : customIps)
                ping(new Ping(new Ping.Config(ip, 5), pingCallback));
            
            if (mReportAddr != null && !mReportAddr.isEmpty())
                for (String ip : customIps)
                    traceroute(new Traceroute(new Traceroute.Config(ip).setThreadSize(3),
                            mReportCustomTracerouteCallback));
            
        }
    }
    
    public void unregister() {
        destroyObj();
    }
    
    public UCNetworkInfo checkNetworkStatus() {
        ConnectivityManager connMgr = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = null;
        if (connMgr != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                networkInfo = connMgr.getActiveNetworkInfo();
            } else {
                networkInfo = checkNetworkStatus_api23_up(connMgr);
            }
        }
        JLog.T(TAG, "networkInfo--->" + (networkInfo == null ? "networkInfo = null" : networkInfo.toString()));
        UCNetworkInfo info = new UCNetworkInfo(networkInfo);
        
        if (networkInfo != null && networkInfo.isConnected()) {
            if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    int strength = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), 5);
                    int speed = wifiInfo.getLinkSpeed();
                    JLog.T(TAG, "[strength]:" + strength + " [speed]:" + speed + WifiInfo.LINK_SPEED_UNITS);
                    info.setSignalStrength(wifiInfo.getRssi());
                }
            } else if (networkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                int strength = 0;
                if (mMobileSignalStrength != null) {
                    if (mMobileSignalStrength.isGsm()) {
                        if (mMobileSignalStrength.getGsmSignalStrength() != 99)
                            strength = mMobileSignalStrength.getGsmSignalStrength() * 2 - 113;
                        else
                            strength = mMobileSignalStrength.getGsmSignalStrength();
                    } else {
                        strength = mMobileSignalStrength.getCdmaDbm();
                    }
                }
                JLog.T(TAG, "[strength]:" + strength + " dbm");
                info.setSignalStrength(strength);
            }
        }
        
        return info;
    }
    
    /**
     * API 23及以上时调用此方法进行网络的检测
     * getAllNetworks() 在API 21后开始使用
     *
     * @param connMgr
     * @return {@link NetworkInfo}
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private NetworkInfo checkNetworkStatus_api23_up(ConnectivityManager connMgr) {
        //获取所有网络连接的信息
        Network[] networks = connMgr.getAllNetworks();
        //用于存放网络连接信息
        StringBuilder sb = new StringBuilder();
        //通过循环将网络信息逐个取出来
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        
        if (networkInfo == null || !networkInfo.isConnectedOrConnecting())
            for (int i = 0; i < networks.length; i++) {
                //获取ConnectivityManager对象对应的NetworkInfo对象
                if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
                    networkInfo = connMgr.getNetworkInfo(networks[i]);
                    break;
                }
            }
        
        return networkInfo;
    }
    
    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mMobileSignalStrength = signalStrength;
        }
    };
    
    private void startMonitorNetStatus() {
        if (isStartMonitorNetStatus)
            return;
        
        mNetStatusReceiver = new UNetStatusReceiver();
        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        mContext.registerReceiver(mNetStatusReceiver, intentFilter);
        isStartMonitorNetStatus = true;
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
    }
    
    private void stopMonitorNetStatus() {
        if (!isStartMonitorNetStatus || mNetStatusReceiver == null)
            return;
        
        mContext.unregisterReceiver(mNetStatusReceiver);
        isStartMonitorNetStatus = false;
        mNetStatusReceiver = null;
    }
    
    private void ping(String host, PingCallback callback) {
        if (TextUtils.isEmpty(host))
            throw new NullPointerException("The parameter (host) is null !");
        Ping ping = new Ping(new Ping.Config(host, 5), callback);
        ping(ping);
    }
    
    private void ping(Ping ping) {
        if (ping == null)
            throw new NullPointerException("The parameter (ping) is null !");
        
        if (mCmdThreadPool != null && !mCmdThreadPool.isShutdown())
            mCmdThreadPool.execute(ping);
    }
    
    private void traceroute(String host, TracerouteCallback callback) {
        if (TextUtils.isEmpty(host))
            throw new NullPointerException("The parameter (host) is null !");
        Traceroute traceroute = new Traceroute(new Traceroute.Config(host).setThreadSize(3),
                callback);
        traceroute(traceroute);
    }
    
    private void traceroute(Traceroute traceroute) {
        if (traceroute == null)
            throw new NullPointerException("The parameter (traceroute) is null !");
        
        if (mCmdThreadPool != null && !mCmdThreadPool.isShutdown())
            mCmdThreadPool.execute(traceroute);
    }
    
    private Boolean flag = false;
    
    private class UNetStatusReceiver extends BroadcastReceiver {
        private final String TAG = getClass().getSimpleName();
        
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null)
                return;
            
            String action = intent.getAction();
            if (TextUtils.isEmpty(action))
                return;
            
            if (!TextUtils.equals(action, ConnectivityManager.CONNECTIVITY_ACTION))
                return;
            
            UCNetworkInfo info = UCNetAnalysisManager.getManager().checkNetworkStatus();
            JLog.D(TAG, "[status]:" + (info == null ? "null" : info.toString()));
            if (mSdkListener != null)
                mSdkListener.onNetworkStatusChanged(info);
            
            if (info == null || info.getNetStatus() == UCNetStatus.NET_STATUS_NOT_REACHABLE)
                return;
            
            synchronized (flag) {
                if (flag)
                    return;
                
                flag = true;
            }
            
            mCurSrcIpInfo = null;
            mDomainResult = null;
            mDomain = null;
            
            if (mCmdThreadPool != null)
                mCmdThreadPool.shutdownNow();
            mCmdThreadPool = Executors.newSingleThreadExecutor();
            
            clearIpList();
            System.gc();
            mCmdThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    JLog.T(TAG, "run thread:" + Thread.currentThread().getId() + " name:" + Thread.currentThread().getName());
                    doGetPublicIpInfo();
                    synchronized (flag) {
                        flag = false;
                    }
                    doGetIpList();
                }
            });
            
        }
    }
    
    private void doGetPublicIpInfo() {
        try {
            Response<PublicIpBean> response = mApiManager.apiGetPublicIpInfo();
            if (response == null || response.body() == null) {
                JLog.I(TAG, "apiGetPublicIpInfo: response is null");
                return;
            }
            
            mCurSrcIpInfo = response.body().getIpInfo();
            mCurSrcIpInfo.setNetType(checkNetworkStatus().getNetStatus().getValue());
        } catch (UCHttpException e) {
            JLog.I(TAG, "apiGetPublicIpInfo error:\n" + e.getMessage());
        }
    }
    
    private void doGetIpList() {
        if (mCurSrcIpInfo == null)
            return;
        
        try {
            Response<UCApiResponseBean<IpListBean>> response = mApiManager.apiGetPingList(mCurSrcIpInfo);
            if (response == null || response.body() == null) {
                JLog.I(TAG, "apiGetPingList: response is null");
                return;
            }
            
            UCApiResponseBean<IpListBean> body = response.body();
            if (body == null) {
                JLog.I(TAG, "apiGetPingList: body is null");
                return;
            }
            
            if (body.getMeta() == null) {
                if (body.getMeta().getCode() != 200)
                    JLog.I(TAG, body.getMeta().toString());
                
                JLog.I(TAG, "meta is null !");
                return;
            }
            
            if (body.getData() == null) {
                JLog.I(TAG, "data is null !");
                return;
            }
            
            mCacheLock.lock();
            if (randomIpList(body.getData())) {
                mIpListCache = body.getData();
                mReportAddr = mIpListCache.getUrl();
                mDomain = mIpListCache.getDomain();
            }
            mCacheLock.unlock();
            
            checkDomain();
            enqueueAuto();
            enqueueCustom();
        } catch (UCHttpException e) {
            JLog.I(TAG, "apiGetPingList error:\n" + e.getMessage());
        }
    }
    
    private boolean randomIpList(IpListBean bean) {
        if (bean == null)
            return false;
        
        if (bean.getInfo() == null || bean.getInfo().isEmpty())
            return false;
        
        if (bean.getUrl() == null || bean.getUrl().isEmpty())
            return false;
        
        Collections.shuffle(bean.getInfo(), new Random(SystemClock.elapsedRealtime()));
        
        return true;
    }
    
    private boolean isCheckingDomain = false;
    private ReentrantLock mLockChecking = new ReentrantLock();
    
    private boolean isCheckingDomain(int flag) {
        mLockChecking.lock();
        isCheckingDomain = flag > 0 ? true : (flag < 0 ? false : isCheckingDomain);
        boolean tmp = isCheckingDomain;
        mLockChecking.unlock();
        return tmp;
    }
    
    private void checkDomain() {
        if (TextUtils.isEmpty(mDomain) || mReportAddr == null || mReportAddr.isEmpty())
            return;
        
        if (isCheckingDomain(0)) {
            return;
        }
        
        isCheckingDomain(1);
        
        mDomainResult = null;
        ping(mDomain, mDomainPingCallback);
    }
    
    private void enqueueAuto() {
        mCacheLock.lock();
        if (mIpListCache == null || mReportAddr == null || mReportAddr.isEmpty()) {
            mCacheLock.unlock();
            return;
        }
        
        List<IpListBean.InfoBean> list = mIpListCache.getInfo();
        mCacheLock.unlock();
        if (list == null || list.isEmpty())
            return;
        
        for (IpListBean.InfoBean info : list) {
            ping(new Ping(new Ping.Config(info.getIp(), 5), mReportPingCallback));
            if (info.isNeedTraceroute())
                traceroute(new Traceroute(new Traceroute.Config(info.getIp()).setThreadSize(3), mReportTracerouteCallback));
        }
    }
    
    private void enqueueCustom() {
        mCustomLock.lock();
        if (mCustomIps == null || mCustomIps.isEmpty() || mReportAddr == null || mReportAddr.isEmpty()) {
            mCustomLock.unlock();
            return;
        }
        
        List<String> list = mCustomIps;
        mCustomLock.unlock();
        
        if (list == null || list.isEmpty())
            return;
        
        for (String ip : list) {
            ping(new Ping(new Ping.Config(ip, 5), mReportCustomPingCallback));
            traceroute(new Traceroute(new Traceroute.Config(ip).setThreadSize(3),
                    mReportCustomTracerouteCallback));
        }
    }
    
    private PingCallback mDomainPingCallback = new PingCallback() {
        @Override
        public void onPingFinish(PingResult result, UCommandStatus status) {
            JLog.D(TAG, String.format("[status]:%s [res]:%s", status.name(), result == null ? "null" : result.toString()));
            isCheckingDomain(-1);
            mDomainResult = new PingDomainResult(result, status);
        }
    };
    
    private PingCallback mReportPingCallback = new PingCallback() {
        @Override
        public void onPingFinish(PingResult result, UCommandStatus status) {
            JLog.D(TAG, String.format("[status]:%s [res]:%s", status.name(), result == null ? "null" : result.toString()));
            if (result == null || status != UCommandStatus.CMD_STATUS_SUCCESSFUL)
                return;
            
            reportPing(result, mReportAddr);
        }
    };
    
    private PingCallback mReportCustomPingCallback = new PingCallback() {
        @Override
        public void onPingFinish(PingResult result, UCommandStatus status) {
            JLog.D(TAG, String.format("[status]:%s [res]:%s", status.name(), result == null ? "null" : result.toString()));
            if (result == null || status != UCommandStatus.CMD_STATUS_SUCCESSFUL)
                return;
            
            reportCustomIpPing(result, mReportAddr);
        }
    };
    
    private void reportCustomIpPing(PingResult result, List<String> reportAddr) {
        reportPing(result, true, reportAddr);
    }
    
    private void reportPing(PingResult result, List<String> reportAddr) {
        reportPing(result, false, reportAddr);
    }
    
    private void reportPing(PingResult result, boolean isCustomIp, List<String> reportAddr) {
        if (result == null || reportAddr == null || reportAddr.isEmpty())
            return;
        
        List<String> reportArrdCache = new ArrayList<>();
        reportArrdCache.addAll(reportAddr);
        
        PingDataBean report = new PingDataBean();
        report.setTimestamp(result.getTimestamp());
        report.setDelay(result.averageDelay());
        report.setLoss(result.lossRate());
        report.setTTL(result.accessTTL());
        report.setDst_ip(result.getTargetIp());
        int pingStatus = 2;
        
        if (result.lossRate() < 100) {
            pingStatus = 0;
        } else {
            if (mDomainResult == null
                    || mDomainResult.getPingResult() == null
                    || !mDomainResult.getStatus().equals(UCommandStatus.CMD_STATUS_SUCCESSFUL)) {
                pingStatus = 2;
            } else {
                pingStatus = mDomainResult.getPingResult().lossRate() < 100 ? 0 : 1;
            }
        }
        
        for (int i = 0, len = reportArrdCache.size(); i < len; i++) {
            if (mCurSrcIpInfo == null)
                return;
            try {
                Response<UCApiResponseBean<MessageBean>> response = mApiManager.apiReportPing(reportArrdCache.get(i), report,
                        pingStatus, isCustomIp, mCurSrcIpInfo, userDefinedData);
                JLog.D(TAG, "[response]:" + (response == null || response.body() == null ? "null" : response.body().toString()));
                if (response != null && response.body() != null && response.body().getMeta() != null
                        && response.body().getMeta().getCode() == 200)
                    break;
            } catch (UCHttpException e) {
                e.printStackTrace();
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }
    }
    
    private TracerouteCallback mReportTracerouteCallback = new TracerouteCallback() {
        @Override
        public void onTracerouteFinish(TracerouteResult result, UCommandStatus status) {
            JLog.D(TAG, String.format("[status]:%s [res]:%s", status.name(), result == null ? "null" : result.toString()));
            if (result == null || status != UCommandStatus.CMD_STATUS_SUCCESSFUL)
                return;
            
            reportTraceroute(result, mReportAddr);
        }
    };
    
    private TracerouteCallback mReportCustomTracerouteCallback = new TracerouteCallback() {
        @Override
        public void onTracerouteFinish(TracerouteResult result, UCommandStatus status) {
            JLog.D(TAG, String.format("[status]:%s [res]:%s", status.name(), result == null ? "null" : result.toString()));
            if (result == null || status != UCommandStatus.CMD_STATUS_SUCCESSFUL)
                return;
            
            reportCustomIpTraceroute(result, mReportAddr);
        }
    };
    
    private void reportCustomIpTraceroute(TracerouteResult result, List<String> reportAddr) {
        reportTraceroute(result, true, reportAddr);
    }
    
    private void reportTraceroute(TracerouteResult result, List<String> reportAddr) {
        reportTraceroute(result, false, reportAddr);
    }
    
    private void reportTraceroute(TracerouteResult result, boolean isCustomIp, List<
            String> reportAddr) {
        if (result == null || reportAddr == null || reportAddr.isEmpty())
            return;
        
        List<String> reportArrdCache = new ArrayList<>();
        reportArrdCache.addAll(reportAddr);
        
        TracerouteDataBean report = new TracerouteDataBean();
        report.setTimestamp(result.getTimestamp());
        List<TracerouteDataBean.RouteInfoBean> routeInfoBeans = new ArrayList<>();
        for (TracerouteNodeResult node : result.getTracerouteNodeResults()) {
            TracerouteDataBean.RouteInfoBean route = new TracerouteDataBean.RouteInfoBean();
            route.setRouteIp(node.getRouteIp());
            route.setDelay(node.averageDelay());
            route.setLoss(node.lossRate());
            routeInfoBeans.add(route);
        }
        report.setRouteInfoList(routeInfoBeans);
        report.setDst_ip(result.getTargetIp());
        
        for (int i = 0, len = reportArrdCache.size(); i < len; i++) {
            if (mCurSrcIpInfo == null)
                return;
            try {
                Response<UCApiResponseBean<MessageBean>> response = mApiManager.apiReportTraceroute(reportArrdCache.get(i), report,
                        isCustomIp, mCurSrcIpInfo, userDefinedData);
                JLog.D(TAG, "[response]:" + (response == null || response.body() == null ? "null" : response.body().toString()));
                if (response != null && response.body() != null && response.body().getMeta() != null
                        && response.body().getMeta().getCode() == 200)
                    break;
            } catch (UCHttpException e) {
                e.printStackTrace();
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }
    }
    
    private void clearIpList() {
        mCacheLock.lock();
        mIpListCache = null;
        if (mReportAddr == null)
            mReportAddr = new ArrayList<>();
        else
            mReportAddr.clear();
        mCacheLock.unlock();
    }
}
