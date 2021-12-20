package org.wolkenproject.core.mocha;

import org.wolkenproject.encoders.Base16;
import org.wolkenproject.exceptions.EmptyProgramCounterException;
import org.wolkenproject.exceptions.MochaException;
import org.wolkenproject.exceptions.UndefOpcodeException;
import org.wolkenproject.utils.Utils;

import java.nio.ByteBuffer;

public class ProgramCounter {
    private ByteBuffer      program;
    private OpcodeRegister  register;

    public ProgramCounter(ByteBuffer program, OpcodeRegister register) {
        this.program = program;
        this.register= register;
        program.position(0);
    }

    public int nextByte() throws EmptyProgramCounterException {
        if (hasNext()) {
            return Byte.toUnsignedInt(program.get());
        }

        throw new EmptyProgramCounterException();
    }

    public int nextShort() throws EmptyProgramCounterException {
        if (remaining() >= 2) {
            return program.getShort();
        }

        throw new EmptyProgramCounterException();
    }

    public int nextUnsignedShort() throws EmptyProgramCounterException {
        if (remaining() >= 2) {
            return program.getChar();
        }

        throw new EmptyProgramCounterException();
    }

    public int nextInt24() throws EmptyProgramCounterException {
        if (remaining() >= 3) {
            return Utils.makeInt((byte) 0, program.get(), program.get(), program.get());
        }

        throw new EmptyProgramCounterException();
    }

    public int nextInt() throws EmptyProgramCounterException {
        if (remaining() >= 4) {
            return program.getInt();
        }

        throw new EmptyProgramCounterException();
    }

    public long nextLong() throws EmptyProgramCounterException {
        if (remaining() >= 8) {
            return program.getLong();
        }

        throw new EmptyProgramCounterException();
    }

    public byte[] next(int length) throws EmptyProgramCounterException {
        if (remaining() >= length) {
            byte array[] = new byte[length];
            program.get(array);

            return array;
        }

        throw new EmptyProgramCounterException();
    }

    public int remaining() {
        return program.remaining();
    }

    public Opcode next() throws UndefOpcodeException, EmptyProgramCounterException {
        if (program.hasRemaining()) {
            int nextOp = program.get();
            return register.getOpcode(nextOp);
        }

        throw new EmptyProgramCounterException();
    }

    public boolean hasNext() {
        return program.hasRemaining();
    }

    public void jump(int jumpLoc) throws MochaException {
        if (program.capacity() < jumpLoc) {
            throw new MochaException("invalid jump location.");
        }

        program.position(jumpLoc);
    }

    public String hexDump() throws MochaException {
        StringBuilder builder   = new StringBuilder();
        int lineLength = 0;

        final int strip     = 20;
        final int indent    = 2;

        for (int i = 0; i < strip; i ++) {
            lineLength ++;
            builder.append("----");
        }

        builder.append("\n ");

        while (hasNext()) {
            Opcode next = next();
            String hexCode = Base16.encode(new byte[] {(byte) next.getIdentifier()});
            lineLength += hexCode.length() + 1;
            builder.append(hexCode).append(" ");

            if (lineLength % strip == 0) {
                builder.append("\n ");
            } else if (lineLength % indent == 0) {
                builder.append("\t");
            }

            if (next.hasVarargs()) {
                int length = 0;

                switch (next.getNumArgs()) {
                    case 1:
                        length = nextByte();
                        break;
                    case 2:
                        length = nextUnsignedShort();
                        break;
                    case 3:
                        length = nextInt24();
                        break;
                    case 4:
                        length = nextInt();
                        break;
                    default:
                        throw new MochaException("Opcode corrupt.");
                }

                String hex = Base16.encode(next(length));
                for (int i = 0; i < hex.length(); i += 2) {
                    builder.append(hex.substring(i, i + 2)).append(" ");
                    lineLength += 3;

                    if (lineLength % strip == 0) {
                        builder.append("\n ");
                    } else if (lineLength % indent == 0) {
                        builder.append("\t");
                    }
                }
            } else if (next.getNumArgs() > 0) {
                String hex = Base16.encode(next(next.getNumArgs()));
                for (int i = 0; i < hex.length(); i += 2) {
                    builder.append(hex.substring(i, i + 2)).append(" ");
                    lineLength += 3;

                    if (lineLength % strip == 0) {
                        builder.append("\n ");
                    } else if (lineLength % indent == 0) {
                        builder.append("\t");
                    }
                }
            }
        }

        return builder.toString();
    }
}
