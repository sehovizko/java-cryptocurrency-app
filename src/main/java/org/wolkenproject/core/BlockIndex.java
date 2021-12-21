package org.wolkenproject.core;

import org.wolkenproject.encoders.Base16;
import org.wolkenproject.exceptions.WolkenException;
import org.wolkenproject.serialization.SerializableI;
import org.wolkenproject.utils.ChainMath;
import org.wolkenproject.utils.HashUtil;
import org.wolkenproject.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;

public class BlockIndex extends SerializableI implements Comparable<BlockIndex> {
    private Block       block;
    private byte        hash[];
    private BigInteger  chainWork;
    private int         height;
    private long        sequenceId;

    public BlockIndex() {
        this(new Block(), BigInteger.ZERO, 0);
    }

    public BlockIndex(Block block, BigInteger chainWork, int height) {
        this.block      = block;
        this.hash       = block.getHashCode();
        this.chainWork  = chainWork;
        this.height     = height;
        this.sequenceId = 0;
    }

    public void setSequenceId(long sequenceId) {
        this.sequenceId = sequenceId;
    }

    public Block getBlock() {
        return block;
    }

    public BigInteger getChainWork() throws WolkenException {
        return chainWork.add(block.getWork());
    }

    public int getHeight() {
        return height;
    }

    public BlockIndex generateNextBlock() throws WolkenException {
        int bits                = ChainMath.calculateNewTarget(this);
        return new BlockIndex(new Block(getHash(), bits), getChainWork(), height + 1);
    }

    @Override
    public void write(OutputStream stream) throws IOException, WolkenException {
        block.write(stream);
        byte chainWork[] = this.chainWork.toByteArray();
        stream.write(chainWork.length);
        stream.write(chainWork);
        Utils.writeInt(height, stream);
    }

    @Override
    public void read(InputStream stream) throws IOException, WolkenException {
        block.read(stream);
        byte length = (byte) stream.read();
        byte chainWork[] = new byte[length];
        stream.read(chainWork);
        this.chainWork = new BigInteger(chainWork);
        stream.read(chainWork, 0, 4);
        height = Utils.makeInt(chainWork);

        hash = block.getHashCode();
    }

    @Override
    public <Type extends SerializableI> Type newInstance(Object... object) throws WolkenException {
        return (Type) new BlockIndex();
    }

    @Override
    public int getSerialNumber() {
        return Context.getInstance().getSerialFactory().getSerialNumber(BlockIndex.class);
    }

    public void recalculateChainWork() throws WolkenException {
        BlockIndex previous = previousBlock();
        if (previous != null) {
            this.chainWork  = previous.getChainWork();
        } else {
            this.chainWork  = BigInteger.ZERO;
        }

        if (hasNext()) {
            next().recalculateChainWork();
        }
    }

    public boolean hasNext() {
        return Context.getInstance().getDatabase().checkBlockExists(getHeight() + 1);
    }

    public boolean hasPrev() {
        return Context.getInstance().getDatabase().checkBlockExists(getHeight() - 1);
    }

    public BlockIndex next() {
        return Context.getInstance().getDatabase().findBlock(getHeight() + 1);
    }

    public BlockIndex previousBlock() {
        return Context.getInstance().getDatabase().findBlock(getHeight() - 1);
    }

    @Override
    public int compareTo(BlockIndex other) {
        try {
            int compare = getChainWork().compareTo(other.getChainWork());

            if (compare > 0) {
                return -1;
            }

            if (compare < 0) {
                return 1;
            }

            if (getSequenceId() < other.getSequenceId()) {
                return -1;
            }

            if (getSequenceId() > other.getSequenceId()) {
                return 1;
            }

            if (getBlock().getTransactionCount() > other.getBlock().getTransactionCount()) {
                return 1;
            }

            if (getBlock().getTransactionCount() < other.getBlock().getTransactionCount()) {
                return -1;
            }

            return -1;
        } catch (WolkenException e) {
            return 0;
        }
    }

    public long getSequenceId() {
        return sequenceId;
    }

    @Override
    public String toString() {
        try {
            return "{" +
                    "block=" + Base16.encode(block.getHashCode()) +
                    ", chainWork=" + getChainWork() +
                    ", height=" + height +
                    ", sequenceId=" + sequenceId +
                    '}';
        } catch (WolkenException e) {
            return "";
        }
    }

    // always use this method when available
    // getBlock().getHashCode() is too expensive
    public byte[] getHash() {
        return hash;
    }

    @Override
    public byte[] checksum() {
        return HashUtil.hash160(getHash());
    }
}
