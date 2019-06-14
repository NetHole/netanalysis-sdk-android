package com.ucloud.library.netanalysis.api.bean;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by joshua on 2018/10/16 14:19.
 * Company: UCloud
 * E-mail: joshua.yin@ucloud.cn
 */
public class IpListBean {
    @SerializedName("info")
    private List<InfoBean> info;
    @SerializedName("url")
    private List<String> url;
    @SerializedName("domain")
    private String domain;
    
    public static class InfoBean {
        @SerializedName("location")
        private String location;
        @SerializedName("ip")
        private String ip;
        /**
         * 0:不traceroute
         * 1：需要traceroute
         */
        @SerializedName("type")
        private int type;
        
        public String getLocation() {
            return location;
        }
        
        public void setLocation(String location) {
            this.location = location;
        }
        
        public String getIp() {
            return ip;
        }
        
        public void setIp(String ip) {
            this.ip = ip;
        }
        
        public int getType() {
            return type;
        }
        
        public void setType(int type) {
            this.type = type;
        }
        
        public boolean isNeedTraceroute() {
            return type == 1;
        }
        
        @Override
        public String toString() {
            return new Gson().toJson(this);
        }
    }
    
    public List<InfoBean> getInfo() {
        return info;
    }
    
    public void setInfo(List<InfoBean> info) {
        this.info = info;
    }
    
    public List<String> getUrl() {
        return url;
    }
    
    public void setUrl(List<String> url) {
        this.url = url;
    }
    
    public String getDomain() {
        return domain;
    }
    
    public void setDomain(String domain) {
        this.domain = domain;
    }
    
    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
