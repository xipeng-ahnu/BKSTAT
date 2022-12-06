package xipeng.statcontroller;
import java.nio.channels.SelectionKey;
import java.io.IOException;
 
/*
 * Edit by xipeng
 * This interface defined what should be done when server has data to read or write
 */

public interface ITcpFlowStatHandler {
	//handle accept
    void handleAccept(SelectionKey key) throws IOException;
    //handle read
    void handleRead(SelectionKey key) throws IOException;
    //handle write
    void handleWrite(SelectionKey key) throws IOException;
}