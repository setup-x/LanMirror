package com.lanmirror.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Dedicated approved audio lane; fixed PCM format, never mixed into the video/control stream. */
public final class AudioLaneWire {
    public static final int MAGIC=0x4c4d4132,VERSION=1,FRAME=1,STATUS=2,MAX_STATUS_BYTES=512;
    private AudioLaneWire(){}
    public static final class Message {
        public final PcmAudioFrame frame;public final String status;
        private Message(PcmAudioFrame frame,String status){this.frame=frame;this.status=status;}
    }
    public static void connect(DataInputStream in,DataOutputStream out,long high,long low)throws IOException{
        if((high|low)==0)throw new IOException("Missing audio capability");
        out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(high);out.writeLong(low);
        out.writeInt(48000);out.writeInt(2);out.writeInt(16);out.flush();
        if(in.readInt()!=1)throw new IOException("Audio capability rejected");
    }
    public static void accept(DataInputStream in,DataOutputStream out,long high,long low)throws IOException{
        if((high|low)==0||in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Invalid audio handshake");
        long receivedHigh=in.readLong(),receivedLow=in.readLong();
        if(((receivedHigh^high)|(receivedLow^low))!=0)throw new IOException("Audio capability rejected");
        if(in.readInt()!=48000||in.readInt()!=2||in.readInt()!=16)throw new IOException("Unsupported PCM format");
        out.writeInt(1);out.flush();
    }
    public static void writeFrame(DataOutputStream out,PcmAudioFrame frame)throws IOException{
        if(frame==null||frame.length<1||frame.length>3840||(frame.length&3)!=0||frame.length>frame.data.length||frame.sequence<0)throw new IOException("Invalid PCM frame");
        out.writeInt(FRAME);out.writeLong(frame.sequence);out.writeLong(frame.capturedAtNs);out.writeInt(frame.length);out.write(frame.data,0,frame.length);out.flush();
    }
    public static void writeStatus(DataOutputStream out,String status)throws IOException{
        byte[] text=ApprovalProtocol.displayName(status).getBytes(StandardCharsets.UTF_8);
        out.writeInt(STATUS);out.writeInt(text.length);out.write(text);out.flush();
    }
    public static Message read(DataInputStream in)throws IOException{
        int type=in.readInt();
        if(type==FRAME){
            long sequence=in.readLong(),captured=in.readLong();int size=in.readInt();
            if(sequence<0||size<1||size>3840||(size&3)!=0)throw new IOException("Invalid PCM record size/sequence");
            byte[] data=new byte[size];in.readFully(data);return new Message(new PcmAudioFrame(sequence,captured,data,size),null);
        }
        if(type==STATUS){
            int size=in.readInt();if(size<1||size>MAX_STATUS_BYTES)throw new IOException("Invalid audio status length");
            byte[] data=new byte[size];in.readFully(data);
            try{return new Message(null,ApprovalProtocol.displayName(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()));}
            catch(CharacterCodingException e){throw new IOException("Malformed audio status",e);}
        }
        throw new IOException("Unknown audio message");
    }
}
