package xipeng.statcontroller;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import org.slf4j.Logger;

import xipeng.statcontroller.ITcpFlowStatHandler;

public class ElephantFlowStatHandler implements ITcpFlowStatHandler {
	private int bufSize;
	//private String logFileName = "D:/statReplyFromSlave";
	private String logFileName = "D:/statReplyFromSlave.log";
	private FileWriter statReplyFile;
	
	public ElephantFlowStatHandler(int bs, Logger log) throws Exception{
		this.bufSize = bs;
	}

	@Override
	public void handleAccept(SelectionKey key) throws IOException {
		// TODO Auto-generated method stub
		SocketChannel clientChan = ((ServerSocketChannel) key.channel()).accept();
        clientChan.configureBlocking(false);
        //register client channel and register interest action read
        clientChan.register(key.selector(), SelectionKey.OP_READ, ByteBuffer.allocate(bufSize));
        //this.statReplyFile = new FileWriter(this.logFileName+System.currentTimeMillis()+".log");
	this.statReplyFile = new FileWriter(this.logFileName);
	}
	
	public static String bytesToHexString(byte[] src){

	    StringBuilder stringBuilder = new StringBuilder("");
	    if (src == null || src.length <= 0) {
	        return null;
	    }
	    for (int i = 0; i < src.length; i++) {
	        int v = src[i] & 0xFF;
	        String hv = Integer.toHexString(v);
	        if (hv.length() < 2) {
	            stringBuilder.append(0);
	        }
	        stringBuilder.append(hv);
	    }
	    return stringBuilder.toString();

	}
	
	public static String bytesToString(byte[] src){

	    StringBuilder stringBuilder = new StringBuilder("");
	    int v;
	    if (src == null || src.length <= 0) {
	        return null;
	    }
	    for (int i = 0; i < src.length; i++) {
	        v = src[i] & 0xFF;
	        if (v==0) {
	        	return stringBuilder.toString();
	        }else if (v==10) {
	        	stringBuilder.append('\n');
	        }else {
	        	stringBuilder.append((char)v);
	        }
	    } 
	    return stringBuilder.toString();
	}

	@Override
	public void handleRead(SelectionKey key) throws IOException {
		// TODO Auto-generated method stub
		SocketChannel clientChan = (SocketChannel) key.channel();
        ByteBuffer buf = (ByteBuffer) key.attachment();
        int bytesRead = clientChan.read(buf);
        //if get -1, means client close the channel
        if (bytesRead == -1){ 
        	System.out.println("Slave Controller Stat Stopped!");
            clientChan.close();
            this.statReplyFile.flush();
            this.statReplyFile.close();
        }else if(bytesRead > 0){
        	buf.flip();
        	byte[] bs = new byte[bytesRead];
        	buf.get(bs,0,bs.length);
//        	for(byte b : bs) {
//        		System.out.printf("%d|",b & 0xFF);
//        	}

            buf.clear();
            /* 
             * write stat reply to file or display, may cause great delay on controller
             */
            //String receiveString = bytesToString(bs);
//            System.out.println("Bytes Read:"+bs.length+"Contents:"+receiveString);
//            if (receiveString!=null && !receiveString.equals("")) {
//            	this.statReplyFile.write(receiveString);
//            	this.statReplyFile.flush();
//            }
        	
        	//if you want to echo back info, change interest action to read and write
        	//key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
	}

	@Override
	public void handleWrite(SelectionKey key) throws IOException {
		// TODO Auto-generated method stub
		// currently do nothing
		
		//ByteBuffer buf = (ByteBuffer) key.attachment();
	    
	    //buf.flip(); 
	    //SocketChannel clntChan = (SocketChannel) key.channel();
	    
	    //clntChan.write(buf);
	    //if (!buf.hasRemaining()){ 
	    	//key.interestOps(SelectionKey.OP_READ);
	    //}
	    //buf.compact(); 
	}

}
