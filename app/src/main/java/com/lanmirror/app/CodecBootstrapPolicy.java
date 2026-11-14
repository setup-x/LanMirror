package com.lanmirror.app;

import java.util.Arrays;
import java.util.List;

/** Conservative Annex-B bootstrap selection, not a complete AVC bitstream validator. */
public final class CodecBootstrapPolicy {
    public static final int MAX_BYTES = 512 * 1024;
    public static final class Configuration {
        public final byte[] sps, pps;
        public final String reason;
        private Configuration(byte[] sps, byte[] pps, String reason) { this.sps=sps; this.pps=pps; this.reason=reason; }
        public boolean usable() { return sps != null && pps != null; }
    }
    private CodecBootstrapPolicy() { }
    public static Configuration select(boolean enabled, List<byte[]> cached) {
        if (!enabled) return unavailable("experiment off");
        if (cached == null || cached.isEmpty() || cached.size() > 8) return unavailable("missing/bounded CSD");
        byte[] sps=null, pps=null; long total=0;
        for (byte[] bytes:cached) {
            if (bytes==null || bytes.length==0 || (total+=bytes.length)>MAX_BYTES) return unavailable("invalid CSD size");
            int start=findStart(bytes,0);
            if(start<0) return unavailable("not Annex-B");
            for(int i=0;i<start;i++) if(bytes[i]!=0) return unavailable("data before start code");
            while(start>=0) {
                int payload=start+prefix(bytes,start), next=findStart(bytes,payload), end=next<0?bytes.length:next;
                while(end>payload && bytes[end-1]==0) end--; // Annex-B trailing_zero_8bits.
                if(end<=payload) return unavailable("empty NAL");
                int header=bytes[payload]&255, type=header&31;
                if((header&128)!=0 || (header&96)==0 || (type!=7 && type!=8)) return unavailable("unsupported NAL/config");
                if(end-payload<(type==7?5:2)) return unavailable("truncated parameter set");
                byte[] normalized=new byte[4+end-payload]; normalized[3]=1;
                System.arraycopy(bytes,payload,normalized,4,end-payload);
                if(type==7) {
                    if(sps!=null && !Arrays.equals(sps,normalized)) return unavailable("multiple distinct SPS; use legacy");
                    sps=normalized;
                } else {
                    if(pps!=null && !Arrays.equals(pps,normalized)) return unavailable("multiple distinct PPS; use legacy");
                    pps=normalized;
                }
                start=next;
            }
        }
        return sps==null || pps==null ? unavailable("SPS/PPS incomplete") : new Configuration(sps,pps,"Annex-B SPS/PPS at configure");
    }
    /** configure auto-submits only for a fresh start; every flush must explicitly resend CSD. */
    public static boolean requiresExplicitSubmission(boolean configureCsdAccepted, boolean freshStart) {
        return !configureCsdAccepted || !freshStart;
    }
    private static Configuration unavailable(String reason) { return new Configuration(null,null,reason); }
    private static int prefix(byte[] b,int at) { return at+3<b.length && b[at]==0 && b[at+1]==0 && b[at+2]==0 && b[at+3]==1 ? 4 : 3; }
    private static int findStart(byte[] b,int from) {
        for(int i=from;i+2<b.length;i++) {
            if(b[i]==0 && b[i+1]==0 && (b[i+2]==1 || (i+3<b.length && b[i+2]==0 && b[i+3]==1))) return i;
        }
        return -1;
    }
}
