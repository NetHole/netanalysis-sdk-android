package com.ucloud.demo.netanalysis;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.AppCompatEditText;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.ucloud.library.netanalysis.UCNetAnalysisManager;
import com.ucloud.library.netanalysis.callback.OnAnalyseListener;
import com.ucloud.library.netanalysis.callback.OnSdkListener;
import com.ucloud.library.netanalysis.exception.UCParamVerifyException;
import com.ucloud.library.netanalysis.module.UserDefinedData;
import com.ucloud.library.netanalysis.module.UCAnalysisResult;
import com.ucloud.library.netanalysis.module.UCNetworkInfo;
import com.ucloud.library.netanalysis.module.UCSdkStatus;
import com.ucloud.library.netanalysis.utils.JLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, OnSdkListener {
    private volatile WeakReference<Handler> mWeakHandler;
    private ProgressDialog mProgressDialog;
    private AlertDialog.Builder mAlertBuilder;
    
    static {
        JLog.SHOW_DEBUG = true;
    }
    
    private UCNetAnalysisManager mUCNetAnalysisManager;
    
    private TextView txt_result;
    private AppCompatEditText edit_host;
    
    private String appKey = "b9e914cc-be4c-5d76-8b15-6953bb669974";
    private String appSecret = "-----BEGIN RSA PUBLIC KEY-----\n" +
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLKoEDmxCXT3PL+QaCcGb1UeC3\n" +
            "M77IRXbbcSOiwRjRt/qqTU46DTK9RCpwdbBf14w/9TBP3/XSGpPcvIrwUwWHGmoa\n" +
            "wf9Wkhlgm8aEKaVnw4LOAdKlANHLdgoOu4iirc1BbfrLila+InVgW1rJRyT+eW+d\n" +
            "kCJrZcWUYD8OMgTYNQIDAQAB\n" +
            "-----END RSA PUBLIC KEY-----";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mUCNetAnalysisManager = UCNetAnalysisManager.createManager(getApplicationContext(), appKey, appSecret);
        
        /**
         * 可以配置自定义需要检测的IP地址
         */
        List<String> ips = new ArrayList<>();
        ips.add("106.75.79.228");   // www.ucloud.cn
        ips.add("115.239.210.27");  // www.baidu.com
        mUCNetAnalysisManager.setCustomIps(ips);
        
        txt_result = findViewById(R.id.txt_result);
        edit_host = findViewById(R.id.edit_host);
        mAlertBuilder = new AlertDialog.Builder(this);
        
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setCanceledOnTouchOutside(false);
        mProgressDialog.setMessage("Waiting...");
        
        mAlertBuilder.setTitle("Error").setMessage("请输入合法的Host")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                }).setCancelable(false);
        
        findViewById(R.id.btn_set_ips).setOnClickListener(this);
        findViewById(R.id.btn_analyse).setOnClickListener(this);
        findViewById(R.id.btn_net_status).setOnClickListener(this);
        
        List<String> list = mUCNetAnalysisManager.getCustomIps();
        StringBuilder sb = new StringBuilder();
        if (list != null && !list.isEmpty())
            for (String ip : list)
                if (!TextUtils.isEmpty(ip))
                    sb.append(ip + "\n");
        
        edit_host.setText(sb.toString().trim());
        
        UserDefinedData.Builder builder = new UserDefinedData.Builder();
        sb = new StringBuilder();
        for (int i = 0, len = 26; i < len; i++) {
            int a = i % 26;
            sb.append(String.format("%s%s", a == 0 && i != 0 ? " " : "", (char) (97 + a)));
        }
        builder.addParam(new UserDefinedData.UserDefinedParam("user_id", sb.toString()));
        try {
            UserDefinedData param = builder.create();
            mUCNetAnalysisManager.register(this, param);
        } catch (UCParamVerifyException e) {
            e.printStackTrace();
        }
    }
    
    private synchronized Handler getHandler() {
        if (mWeakHandler == null || mWeakHandler.get() == null) {
            mWeakHandler = new WeakReference<>(new Handler(Looper.getMainLooper()));
        }
        
        return mWeakHandler.get();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
    }
    
    @Override
    protected void onStop() {
        super.onStop();
    }
    
    @Override
    protected void onDestroy() {
        mUCNetAnalysisManager.unregister();
        UCNetAnalysisManager.destroy();
        super.onDestroy();
    }
    
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_analyse: {
                edit_host.clearFocus();
                txt_result.setText("");
                mProgressDialog.show();
                mUCNetAnalysisManager.analyse(new OnAnalyseListener() {
                    @Override
                    public void onAnalysed(final UCAnalysisResult result) {
                        JLog.E("TEST", result.toString());
                        getHandler().post(new Runnable() {
                            @Override
                            public void run() {
                                mProgressDialog.dismiss();
                                try {
                                    txt_result.setText(new JSONObject(result.toString()).toString(4));
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                });
                
                break;
            }
            case R.id.btn_set_ips: {
                edit_host.clearFocus();
                txt_result.setText("");
                String host = edit_host.getText().toString().trim();
                if (TextUtils.isEmpty(host)) {
                    mAlertBuilder.create().show();
                    return;
                }
                
                List<String> ips = new ArrayList<>();
                String[] cache = host.split("\n");
                for (String str : cache) {
                    if (!TextUtils.isEmpty(str))
                        ips.add(str);
                }
                mUCNetAnalysisManager.setCustomIps(ips);
                Toast.makeText(this, "配置成功", Toast.LENGTH_SHORT).show();
                
                break;
            }
            case R.id.btn_net_status: {
                UCNetworkInfo networkInfo = mUCNetAnalysisManager.checkNetworkStatus();
                txt_result.setText(networkInfo == null ? "null" : networkInfo.toString());
                break;
            }
        }
    }
    
    @Override
    public void onRegister(UCSdkStatus status) {
        Toast.makeText(this, status.name(), Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onNetworkStatusChanged(UCNetworkInfo networkInfo) {
        Toast.makeText(this, networkInfo.toString(), Toast.LENGTH_SHORT).show();
    }
}