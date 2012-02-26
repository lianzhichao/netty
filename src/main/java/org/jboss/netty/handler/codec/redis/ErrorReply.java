package org.jboss.netty.handler.codec.redis;

import org.jboss.netty.buffer.ChannelBuffer;

import java.io.IOException;

/**
 * Created by IntelliJ IDEA.
 * User: sam
 * Date: 7/29/11
 * Time: 10:23 AM
 * To change this template use File | Settings | File Templates.
 */
public class ErrorReply extends Reply {
    public static final char MARKER = '-';
    private static final byte[] ERR = "ERR ".getBytes(UTF_8);
    public final String error;

    public ErrorReply(String error) {
        this.error = error;
    }

    public void write(ChannelBuffer os) throws IOException {
        os.writeByte(MARKER);
        os.writeBytes(ERR);
        os.writeBytes(error.getBytes(UTF_8));
        os.writeBytes(Command.CRLF);
    }
}
