package com.lanmirror.core.api;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;

public final class CodecBootstrapPolicyTest {
    private static int checks;
    private static final byte[] SPS={0x67,0x42,0,0x2a,(byte)0x80};
    private static final byte[] PPS={0x68,(byte)0xc0};
    private static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
    private static byte[] nal(int prefix,byte[] data){byte[] b=new byte[prefix+data.length];b[prefix-1]=1;System.arraycopy(data,0,b,prefix,data.length);return b;}
    private static byte[] concat(byte[]... arrays){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] a:arrays)out.write(a,0,a.length);return out.toByteArray();}
    private static CodecBootstrapPolicy.Configuration select(byte[]... arrays){return CodecBootstrapPolicy.select(true,Arrays.asList(arrays));}
    public static void main(String[] args){
        byte[] s3=nal(3,SPS),s4=nal(4,SPS),p3=nal(3,PPS),p4=nal(4,PPS);
        CodecBootstrapPolicy.Configuration config=select(s3,p4);
        check(config.usable(),"3/4-byte AnnexB supported");
        check(Arrays.equals(config.sps,s4)&&Arrays.equals(config.pps,p4),"Canonical csd-0 SPS and csd-1 PPS");
        check(select(concat(s3,p4)).usable(),"Combined parameter sets");
        config=select(concat(p3,s4),s3,p4,concat(s4,p3));
        check(config.usable()&&Arrays.equals(config.sps,s4)&&Arrays.equals(config.pps,p4),"Duplicates/order normalized");
        check(select(concat(new byte[]{0,0},s3),concat(p4,new byte[]{0,0})).usable(),"AnnexB leading/trailing zero bytes");
        byte[] source=concat(s3,p4);config=select(source);source[3]=0;
        check(config.usable()&&config.sps[4]==0x67,"Snapshot independent of cached storage");
        check(!CodecBootstrapPolicy.select(false,Arrays.asList(s3,p4)).usable(),"Default disabled never changes configure");
        check(!CodecBootstrapPolicy.select(false,null).usable(),"Disabled avoids parsing");
        check(!CodecBootstrapPolicy.select(true,null).usable(),"Missing list fallback");
        check(!CodecBootstrapPolicy.select(true,Collections.<byte[]>emptyList()).usable(),"Empty fallback");
        check(!select(s3).usable()&&!select(p4).usable(),"Incomplete pair fallback");
        check(!select(SPS,PPS).usable(),"No AnnexB rejects raw/AVCC");
        check(!select(new byte[]{0,0,0,1},p4).usable(),"Empty NAL fallback");
        check(!select(concat(s3,new byte[]{0,0,1}),p4).usable(),"Trailing empty NAL fallback");
        check(!select(nal(4,new byte[]{0x67,0x42,0,0x2a}),p4).usable(),"Truncated SPS fallback");
        check(!select(s3,nal(3,new byte[]{0x68})).usable(),"Truncated PPS fallback");
        check(!select(nal(4,new byte[]{(byte)0xe7,0x42,0,0x2a,(byte)0x80}),p4).usable(),"Forbidden zero bit must be zero");
        check(!select(nal(3,new byte[]{7,0x42,0,0x2a,(byte)0x80}),p4).usable(),"Parameter set ref-idc nonzero");
        check(!select(concat(new byte[]{1},s3),p4).usable(),"Nonzero garbage prefix fallback");
        check(!select(concat(s3,nal(3,new byte[]{0x65,1,2})),p4).usable(),"Video inside config falls back");
        check(!select(s3,null,p4).usable(),"Null cache entry fallback");
        check(!select(s3,new byte[0],p4).usable(),"Empty cache entry fallback");
        byte[] other=SPS.clone();other[3]=0x29;
        check(!select(s3,nal(3,other),p4).usable(),"Multiple distinct SPS conservatively fallback");
        check(!select(s3,p4,nal(4,new byte[]{0x68,(byte)0xd0})).usable(),"Multiple distinct PPS fallback");
        byte[][] many=new byte[9][];Arrays.fill(many,concat(s3,p4));
        check(!select(many).usable(),"Cache item bound");
        check(!select(new byte[CodecBootstrapPolicy.MAX_BYTES+1]).usable(),"Byte bound before parse");
        check(!CodecBootstrapPolicy.requiresExplicitSubmission(true,true),"Fresh configured start auto-submits CSD");
        check(CodecBootstrapPolicy.requiresExplicitSubmission(true,false),"Flush after configured start MUST resend CSD");
        check(CodecBootstrapPolicy.requiresExplicitSubmission(false,true),"Legacy fresh start explicitly sends CSD");
        check(CodecBootstrapPolicy.requiresExplicitSubmission(false,false),"Legacy flush explicitly resends CSD");
        for(int i=0;i<8;i++)check(select(i%2==0?concat(s3,p4):concat(p3,s4)).usable(),"Deterministic alternating combined order");
        System.out.println("PASS: "+checks+" codec bootstrap assertions (AnnexB bounds/normalization/fallback, opt-in and flush resend contract; NOT hardware performance)");
    }
}
