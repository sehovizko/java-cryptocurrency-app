package org.wolkenproject.network.messages;

import org.wolkenproject.core.Ancestors;
import org.wolkenproject.core.Block;
import org.wolkenproject.core.Context;
import org.wolkenproject.exceptions.WolkenException;
import org.wolkenproject.network.Message;
import org.wolkenproject.network.Node;
import org.wolkenproject.network.ResponseMetadata;
import org.wolkenproject.network.Server;
import org.wolkenproject.serialization.SerializableI;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class RequestCommonAncestorChain extends Message {
    private Ancestors ancestors;

    public RequestCommonAncestorChain(int version, Ancestors ancestors) {
        super(version, Flags.Request);
        this.ancestors = ancestors;
    }

    @Override
    public void executePayload(Server server, Node node) {
        node.sendMessage(new FoundCommonAncestor(ancestors.findCommon(), getUniqueMessageIdentifier()));
    }

    @Override
    public void writeContents(OutputStream stream) throws IOException, WolkenException {
        ancestors.write(stream);
    }

    @Override
    public void readContents(InputStream stream) throws IOException, WolkenException {
        ancestors.read(stream);
    }

    @Override
    public <Type> Type getPayload() {
        return (Type) ancestors;
    }

    @Override
    public ResponseMetadata getResponseMetadata() {
        return (msg)->{
            return 0;
        };
    }

    @Override
    public <Type extends SerializableI> Type newInstance(Object... object) throws WolkenException {
        return (Type) new RequestCommonAncestorChain(0, new Ancestors(new byte[Block.UniqueIdentifierLength]));
    }

    @Override
    public int getSerialNumber() {
        return Context.getInstance().getSerialFactory().getSerialNumber(RequestCommonAncestorChain.class);
    }
}
