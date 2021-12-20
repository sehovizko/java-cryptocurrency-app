package org.wolkenproject.network.messages;

import org.wolkenproject.core.Context;
import org.wolkenproject.exceptions.WolkenException;
import org.wolkenproject.network.Message;
import org.wolkenproject.network.Node;
import org.wolkenproject.network.ResponseMetadata;
import org.wolkenproject.network.Server;
import org.wolkenproject.serialization.SerializableI;
import org.wolkenproject.utils.Logger;
import org.wolkenproject.utils.VarInt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class CheckoutMessage extends Message {
    public static final class Reason {
        public static final int
            None = 0,
            SelfConnect = 1;
    }

    private int reason;

    public CheckoutMessage(int reason) {
        super(Flags.Notify, Context.getInstance().getNetworkParameters().getVersion());
        this.reason = reason;
    }

    @Override
    public void executePayload(Server server, Node node) {
        Logger.alert("node ${n} requested to disconnect for reason ${r}", node.getInetAddress(), reason);

        try {
            node.close();
        } catch (IOException e) {
            Logger.alert("could not disconnect from node properly.");
            e.printStackTrace();
        }

        // this check should happen but it's not crucial
//        if (reason == Reason.SelfConnect) {
            // there is no version info if "checkout" is sent
//            if (!node.getVersionInfo().isSelfConnection(server.getNonce())) {
//                node.increaseErrors(1);
//            }
//        }
    }

    @Override
    public void onSend(Node node) {
        super.onSend(node);
        try {
            Logger.alert("connection termination requested ${r}.", reason);
            node.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void writeContents(OutputStream stream) throws IOException, WolkenException {
        VarInt.writeCompactUInt32(reason, false, stream);
    }

    @Override
    public void readContents(InputStream stream) throws IOException, WolkenException {
        reason = VarInt.readCompactUInt32(false, stream);
    }

    @Override
    public <Type> Type getPayload() {
        return null;
    }

    @Override
    public ResponseMetadata getResponseMetadata() {
        return null;
    }

    @Override
    public <Type extends SerializableI> Type newInstance(Object... object) throws WolkenException {
        return (Type) new CheckoutMessage(0);
    }

    @Override
    public int getSerialNumber() {
        return Context.getInstance().getSerialFactory().getSerialNumber(CheckoutMessage.class);
    }
}
