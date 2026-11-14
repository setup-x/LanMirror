package com.lanmirror.app;

import java.io.*;
import java.net.*;
import java.util.function.BooleanSupplier;

/** Approval stage only: polls peer liveness, never consumes an approved SmoothWire handshake. */
public final class ApprovalWaiter {
    private ApprovalWaiter(){}
    public static AdmissionSlots.Snapshot await(DataInputStream input,Socket socket,AdmissionSlots admissions,long id,BooleanSupplier stopped)throws IOException {
        socket.setSoTimeout(250);
        for(;;){
            if(stopped.getAsBoolean())throw new IOException("接收端已关闭或请求已取消");
            AdmissionSlots.Snapshot state=admissions.expire(id,System.nanoTime());
            if(state==null)throw new IOException("请求状态已结束");
            if(state.phase!=AdmissionSlots.Phase.PENDING||state.choice!=null)return state;
            try{int next=input.read();if(next<0)throw new EOFException("发送端在确认前已取消");throw new IOException("批准前不允许发送媒体或后续握手数据");}
            catch(SocketTimeoutException timeout){/* Bound UI decision/EOF response without writing on the UI thread. */}
        }
    }
}
