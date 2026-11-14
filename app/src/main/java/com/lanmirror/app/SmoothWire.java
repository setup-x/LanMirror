package com.lanmirror.app;

import java.io.*;

/** Independent smooth protocol, NOT legacy Wire v2. Framing big-endian; no encryption. */
public final class SmoothWire {
    public static final int PORT=7237, MAGIC=0x4c4d5331, VERSION=1;
    public static final int CONFIG=1,VIDEO=2,REQUEST_SYNC=3,END=4,CONFIG_ACK=5;
    public static final int MAX_PACKET=4*1024*1024,MAX_VIDEO_BYTES=MAX_PACKET-64;
    public static final int MAX_CSD_BYTES=512*1024,MAX_CSD_ITEMS=8;
    private static final int VIDEO_HEADER=36;
    private SmoothWire(){}
    public static final class Hello {
        public final boolean udp;public final long sessionId;public final int udpPort;
        private Hello(boolean udp,long sessionId,int udpPort){this.udp=udp;this.sessionId=sessionId;this.udpPort=udpPort;}
    }
    public static final class Config {
        public final long generation;public final int width,height;public final byte[][] csd;
        /** Deep copy; public nested arrays must be treated read-only by all consumers. */
        public Config(long generation,int width,int height,byte[][] csd){
            validateConfig(generation,width,height,csd);this.generation=generation;this.width=width;this.height=height;
            this.csd=new byte[csd.length][];for(int i=0;i<csd.length;i++)this.csd[i]=csd[i].clone();
        }
    }
    public static final class Message {
        public final int type;public final Config config;public final StreamFrame frame;public final long generation;
        private Message(int type,Config config,StreamFrame frame,long generation){this.type=type;this.config=config;this.frame=frame;this.generation=generation;}
    }
    private static void pin(String pin)throws IOException{if(pin==null||!pin.matches("[0-9]{6}"))throw new IOException("Six digit PIN required");}
    public static Hello connect(DataInputStream in,DataOutputStream out,String pin,boolean udp)throws IOException{
        pin(pin);synchronized(out){out.writeInt(MAGIC);out.writeInt(VERSION);out.writeBoolean(udp);out.writeUTF(pin);out.flush();}
        int accepted=in.readInt();if(accepted!=1)throw new IOException("Smooth pairing/protocol rejected");
        boolean selected=in.readBoolean();long session=in.readLong();int port=in.readInt();
        if(selected!=udp||session==0||(selected&&(port<1||port>65535))||(!selected&&port!=0))throw new IOException("Invalid smooth negotiation");
        return new Hello(selected,session,port);
    }
    public static boolean accept(DataInputStream in,DataOutputStream out,String pin,long sessionId,int udpPort)throws IOException{
        pin(pin);int magic=in.readInt(),version=in.readInt();boolean udp=in.readBoolean();String supplied=in.readUTF();
        boolean ok=magic==MAGIC&&version==VERSION&&pin.equals(supplied)&&sessionId!=0&&(!udp||(udpPort>0&&udpPort<=65535));
        synchronized(out){out.writeInt(ok?1:0);if(ok){out.writeBoolean(udp);out.writeLong(sessionId);out.writeInt(udp?udpPort:0);}out.flush();}
        if(!ok)throw new IOException("Smooth pairing/protocol rejected");return udp;
    }
    private static void validateConfig(long gen,int w,int h,byte[][] csd){
        if(gen<0||w<=0||h<=0||w>8192||h>8192||(long)w*h>16777216L||csd==null||csd.length>MAX_CSD_ITEMS)throw new IllegalArgumentException("Invalid smooth CONFIG");
        long total=0;for(byte[] c:csd){if(c==null||c.length==0||(total+=c.length)>MAX_CSD_BYTES)throw new IllegalArgumentException("Invalid codec configuration size");}
    }
    public static void writeConfig(DataOutputStream out,Config config)throws IOException{
        if(config==null)throw new IOException("Missing CONFIG");
        try{validateConfig(config.generation,config.width,config.height,config.csd);}catch(IllegalArgumentException e){throw new IOException(e);}
        int length=20;for(byte[] c:config.csd)length+=4+c.length;
        synchronized(out){out.writeInt(CONFIG);out.writeInt(length);out.writeLong(config.generation);out.writeInt(config.width);out.writeInt(config.height);out.writeInt(config.csd.length);for(byte[] c:config.csd){out.writeInt(c.length);out.write(c);}out.flush();}
    }
    public static void writeConfigAck(DataOutputStream out,long gen)throws IOException{if(gen<0)throw new IOException("Invalid generation");synchronized(out){out.writeInt(CONFIG_ACK);out.writeInt(8);out.writeLong(gen);out.flush();}}
    public static void writeVideo(DataOutputStream out,StreamFrame frame)throws IOException{
        if(frame==null||frame.referenceCount()<=0)throw new IOException("Released/missing video frame");
        synchronized(out){out.writeInt(VIDEO);out.writeInt(VIDEO_HEADER+frame.length);out.writeLong(frame.generation);out.writeLong(frame.sequence);out.writeLong(frame.ptsUs);out.writeInt(frame.flags);out.writeLong(frame.encodedAtNs);out.write(frame.data,frame.offset,frame.length);out.flush();}
    }
    private static void empty(DataOutputStream out,int type)throws IOException{synchronized(out){out.writeInt(type);out.writeInt(0);out.flush();}}
    public static void writeSync(DataOutputStream out)throws IOException{empty(out,REQUEST_SYNC);}
    public static void writeEnd(DataOutputStream out)throws IOException{empty(out,END);}
    public static Message readMessage(DataInputStream in,FrameBufferPool pool)throws IOException{
        int type=in.readInt(),length=in.readInt();if(length<0||length>MAX_PACKET)throw new IOException("Invalid smooth packet length");
        if(type==VIDEO){
            if(length<VIDEO_HEADER||length-VIDEO_HEADER>MAX_VIDEO_BYTES)throw new IOException("Invalid VIDEO length");
            long gen=in.readLong(),seq=in.readLong(),pts=in.readLong();int flags=in.readInt();long stamp=in.readLong();int bytes=length-VIDEO_HEADER;
            if(gen<0||seq<0)throw new IOException("Invalid VIDEO identity");
            byte[] data;try{data=pool==null?new byte[bytes]:pool.acquire(bytes);}catch(RuntimeException e){throw new IOException("Video pool unavailable",e);}
            boolean transferred=false;try{in.readFully(data,0,bytes);StreamFrame frame=new StreamFrame(gen,seq,pts,flags,stamp,System.nanoTime(),data,0,bytes,pool);transferred=true;return new Message(type,null,frame,gen);}finally{if(!transferred&&pool!=null)pool.recycle(data);}
        }
        if(type==CONFIG){
            if(length<20||length>20+4*MAX_CSD_ITEMS+MAX_CSD_BYTES)throw new IOException("Invalid CONFIG length");
            byte[] body=new byte[length];in.readFully(body);DataInputStream data=new DataInputStream(new ByteArrayInputStream(body));
            long gen=data.readLong();int width=data.readInt(),height=data.readInt(),count=data.readInt();
            if(count<0||count>MAX_CSD_ITEMS)throw new IOException("Invalid CSD count");byte[][] csd=new byte[count][];int total=0;
            for(int i=0;i<count;i++){int size=data.readInt();if(size<=0||size>data.available()||(long)total+size>MAX_CSD_BYTES)throw new IOException("Invalid CSD length");csd[i]=new byte[size];data.readFully(csd[i]);total+=size;}
            if(data.available()!=0)throw new IOException("Trailing CONFIG bytes");
            try{return new Message(type,new Config(gen,width,height,csd),null,gen);}catch(IllegalArgumentException e){throw new IOException("Invalid CONFIG",e);}
        }
        if(type==CONFIG_ACK){if(length!=8)throw new IOException("Invalid ACK length");long gen=in.readLong();if(gen<0)throw new IOException("Invalid ACK generation");return new Message(type,null,null,gen);}
        if(type==REQUEST_SYNC||type==END){if(length!=0)throw new IOException("Nonempty control packet");return new Message(type,null,null,-1);}
        throw new IOException("Unknown smooth packet type");
    }
}
