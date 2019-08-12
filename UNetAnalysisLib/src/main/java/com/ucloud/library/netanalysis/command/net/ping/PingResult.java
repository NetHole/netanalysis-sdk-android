package com.ucloud.library.netanalysis.command.net.ping;


import com.ucloud.library.netanalysis.command.bean.UCommandStatus;
import com.ucloud.library.netanalysis.parser.JsonSerializable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by joshua on 2018/9/5 18:45.
 * Company: UCloud
 * E-mail: joshua.yin@ucloud.cn
 */
public class PingResult implements JsonSerializable {
    private String targetIp;
    private List<SinglePackagePingResult> pingPackages;
    private long timestamp;
    
    protected PingResult(String targetIp, long timestamp) {
        this.targetIp = targetIp;
        this.timestamp = timestamp;
        this.pingPackages = new ArrayList<>();
    }
    
    public String getTargetIp() {
        return targetIp;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public List<SinglePackagePingResult> getPingPackages() {
        return pingPackages;
    }
    
    PingResult setPingPackages(List<SinglePackagePingResult> pingPackages) {
        if (pingPackages == null)
            this.pingPackages.clear();
        else
            this.pingPackages.addAll(pingPackages);
        
        return this;
    }
    
    public int averageDelay() {
        int count = 0;
        float total = 0.f;
        for (SinglePackagePingResult pkg : pingPackages) {
            if (pkg == null || pkg.getStatus() != UCommandStatus.CMD_STATUS_SUCCESSFUL || pkg.delay == 0.f)
                continue;
            
            count++;
            total += pkg.delay;
        }
        
        return Math.round(total / count);
    }
    
    public int lossRate() {
        int loss = 0;
        float total = pingPackages.size();
        for (SinglePackagePingResult pkg : pingPackages) {
            if (pkg == null || pkg.getStatus() != UCommandStatus.CMD_STATUS_SUCCESSFUL || pkg.delay == 0.f)
                loss++;
        }
        
        return Math.round(loss / total * 100);
    }
    
    public int accessTTL() {
        for (SinglePackagePingResult pkg : pingPackages) {
            if (pkg == null || pkg.getStatus() != UCommandStatus.CMD_STATUS_SUCCESSFUL || pkg.delay == 0.f)
                continue;
            
            return pkg.TTL;
        }
        
        return 0;
    }
    
    @Override
    public String toString() {
        return toJson().toString();
    }
    
    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray jarr = new JSONArray();
        if (pingPackages != null && !pingPackages.isEmpty()) {
            for (SinglePackagePingResult result : pingPackages) {
                if (result == null || result.toJson().length() == 0)
                    continue;
                
                jarr.put(result.toJson());
            }
        }
        try {
            json.put("targetIp", targetIp);
            json.put("timestamp", timestamp);
            json.put("avgDelay", averageDelay());
            json.put("loss", lossRate());
            json.put("pingPackages", jarr);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return json;
    }
}
