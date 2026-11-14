package com.lanmirror.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Approval-only prelude on the SAME TCP stream, then unchanged SmoothWire negotiation.
 * Receiver approval grants one connection; ephemeral PIN is not a user-entered credential or encryption.
 */
public final class ApprovalProtocol {
    public static final int PORT=7239, MAGIC=0x4c4d4131, VERSION=1, AUDIO_VERSION=2;
    public static final int WAIT=0,ACCEPT=1,REJECT=2,MAX_NAME_BYTES=192;
    public static final int APPROVAL_TIMEOUT_MS=30000,CLIENT_TIMEOUT_MS=40000;
    private ApprovalProtocol(){}
    public static final class Request {
        public final String name;
        public final boolean udp,audio;
        private Request(String name,boolean udp,boolean audio){this.name=name;this.udp=udp;this.audio=audio;}
    }
    public static Request readRequest(DataInputStream in)throws IOException{
        if(in.readInt()!=MAGIC)throw new IOException("不支持的连接申请协议");
        int version=in.readInt();if(version!=VERSION&&version!=AUDIO_VERSION)throw new IOException("不支持的连接申请协议");
        int length=in.readInt();if(length<1||length>MAX_NAME_BYTES)throw new IOException("设备名称长度非法");
        byte[] bytes=new byte[length];in.readFully(bytes);
        String name;
        try{name=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new IOException("设备名称不是有效UTF-8",e);}
        name=displayName(name);
        int mode=in.readUnsignedByte();if(mode>1)throw new IOException("未知视频传输模式");
        return new Request(name,mode==1,version==AUDIO_VERSION);
    }
    public static String clientRequest(DataInputStream in,DataOutputStream out,String name,boolean udp)throws IOException{
        byte[] bytes=displayName(name).getBytes(StandardCharsets.UTF_8);
        if(bytes.length<1||bytes.length>MAX_NAME_BYTES)throw new IOException("设备名称过长");
        synchronized(out){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(bytes.length);out.write(bytes);out.writeByte(udp?1:0);out.flush();}
        int state=in.readInt();if(state==WAIT)state=in.readInt();
        if(state==REJECT)throw new IOException("接收端拒绝："+readText(in,512));
        if(state!=ACCEPT)throw new IOException("接收端审批响应非法");
        String token=readText(in,6);if(!token.matches("[0-9]{6}"))throw new IOException("接收端会话凭据非法");return token;
    }
    public static final class AudioGrant {
        public final String pin,status;public final int port;public final long high,low;
        public AudioGrant(String pin,int port,long high,long low,String status)throws IOException{
            if(pin==null||!pin.matches("[0-9]{6}")||port<0||port>65535||(port==0?(high|low)!=0:(high|low)==0))throw new IOException("Invalid audio approval grant");
            this.pin=pin;this.port=port;this.high=high;this.low=low;this.status=displayName(status);
        }
    }
    public static AudioGrant clientRequestWithAudio(DataInputStream in,DataOutputStream out,String name,boolean udp)throws IOException{
        byte[] bytes=displayName(name).getBytes(StandardCharsets.UTF_8);
        synchronized(out){out.writeInt(MAGIC);out.writeInt(AUDIO_VERSION);out.writeInt(bytes.length);out.write(bytes);out.writeByte(udp?1:0);out.flush();}
        int state;try{state=in.readInt();}catch(EOFException e){throw new IOException("接收端未完成声音协商；请升级1.8或取消内部声音后重连",e);}
        if(state==WAIT)state=in.readInt();
        if(state==REJECT)throw new IOException("接收端拒绝："+readText(in,512));if(state!=ACCEPT)throw new IOException("接收端审批响应非法");
        String pin=readText(in,6);int port=in.readInt();long high=in.readLong(),low=in.readLong();String status=readText(in,MAX_NAME_BYTES);
        return new AudioGrant(pin,port,high,low,status);
    }
    public static void writeAcceptedAudio(DataOutputStream out,AudioGrant grant)throws IOException{
        synchronized(out){out.writeInt(ACCEPT);writeText(out,grant.pin);out.writeInt(grant.port);out.writeLong(grant.high);out.writeLong(grant.low);writeText(out,grant.status);out.flush();}
    }
    public static void writeWaiting(DataOutputStream out)throws IOException{synchronized(out){out.writeInt(WAIT);out.flush();}}
    public static void writeAccepted(DataOutputStream out,String ephemeralPin)throws IOException{
        if(ephemeralPin==null||!ephemeralPin.matches("[0-9]{6}"))throw new IOException("Invalid approval token");
        synchronized(out){out.writeInt(ACCEPT);writeText(out,ephemeralPin);out.flush();}
    }
    public static void writeRejected(DataOutputStream out,String reason)throws IOException{
        String text=reason==null?"未获允许":displayName(reason);
        synchronized(out){out.writeInt(REJECT);writeText(out,text);out.flush();}
    }
    private static void writeText(DataOutputStream out,String text)throws IOException{byte[] data=text.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
    private static String readText(DataInputStream in,int max)throws IOException{int size=in.readInt();if(size<1||size>max)throw new IOException("Invalid approval response length");byte[] data=new byte[size];in.readFully(data);return new String(data,StandardCharsets.UTF_8);}
    /** Untrusted names are labels, not device identity; strip controls/bidi formatting and cap by UTF-8 bytes. */
    public static String displayName(String supplied){
        if(supplied==null)return "Android设备";
        StringBuilder result=new StringBuilder();int bytes=0;
        for(int i=0;i<supplied.length();){int c=supplied.codePointAt(i);i+=Character.charCount(c);
            if(Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c>=0xd800&&c<=0xdfff)continue;
            int size=c<=0x7f?1:c<=0x7ff?2:c<=0xffff?3:4;if(bytes+size>MAX_NAME_BYTES)break;result.appendCodePoint(c);bytes+=size;
        }
        String text=result.toString().trim();return text.isEmpty()?"Android设备":text;
    }
}
