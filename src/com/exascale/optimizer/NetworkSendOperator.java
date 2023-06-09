package com.exascale.optimizer;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import com.exascale.compression.CompressedOutputStream;
import com.exascale.managers.HRDBMSWorker;
import com.exascale.misc.DataEndMarker;
import com.exascale.misc.HrdbmsType;
import com.exascale.misc.MyDate;
import com.exascale.tables.Plan;

public class NetworkSendOperator implements Operator, Serializable
{
	protected static final int WORKER_PORT = Integer.parseInt(HRDBMSWorker.getHParms().getProperty("port_number"));

	protected static Charset cs = StandardCharsets.UTF_8;
	private static sun.misc.Unsafe unsafe;
	private static long offset;

	static
	{
		try
		{
			final Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			unsafe = (sun.misc.Unsafe)f.get(null);
			final Field fieldToUpdate = String.class.getDeclaredField("value");
			// get unsafe offset to this field
			offset = unsafe.objectFieldOffset(fieldToUpdate);
		}
		catch (final Exception e)
		{
			unsafe = null;
		}
	}
	protected MetaData meta;
	protected Operator child;
	protected Operator parent;
	protected HashMap<String, String> cols2Types;
	protected HashMap<String, Integer> cols2Pos;
	protected TreeMap<Integer, String> pos2Col;
	protected transient Socket sock;
	protected transient OutputStream out;
	protected int node;
	protected int numParents;
	protected boolean started = false;
	protected boolean numpSet = false;
	protected boolean cardSet = false;

	protected CharsetEncoder ce = cs.newEncoder();
	protected transient AtomicLong received;
	protected transient volatile boolean demReceived;

	public NetworkSendOperator(final int node, final MetaData meta)
	{
		this.meta = meta;
		this.node = node;
		received = new AtomicLong(0);
	}

	protected NetworkSendOperator()
	{
		received = new AtomicLong(0);
	}

	public static NetworkSendOperator deserialize(final InputStream in, final HashMap<Long, Object> prev) throws Exception
	{
		final NetworkSendOperator value = (NetworkSendOperator)unsafe.allocateInstance(NetworkSendOperator.class);
		prev.put(OperatorUtils.readLong(in), value);
		value.meta = new MetaData();
		value.child = OperatorUtils.deserializeOperator(in, prev);
		value.parent = OperatorUtils.deserializeOperator(in, prev);
		value.cols2Types = OperatorUtils.deserializeStringHM(in, prev);
		value.cols2Pos = OperatorUtils.deserializeStringIntHM(in, prev);
		value.pos2Col = OperatorUtils.deserializeTM(in, prev);
		value.node = OperatorUtils.readInt(in);
		value.numParents = OperatorUtils.readInt(in);
		value.started = OperatorUtils.readBool(in);
		value.numpSet = OperatorUtils.readBool(in);
		value.cardSet = OperatorUtils.readBool(in);
		value.ce = cs.newEncoder();
		value.received = new AtomicLong(0);
		value.demReceived = false;
		return value;
	}

	private static byte[] toBytesDEM()
	{
		final byte[] retval = new byte[9];
		retval[0] = 0;
		retval[1] = 0;
		retval[2] = 0;
		retval[3] = 5;
		retval[4] = 0;
		retval[5] = 0;
		retval[6] = 0;
		retval[7] = 1;
		retval[8] = 5;
		return retval;
	}

	private static byte[] toBytesException(final Object v)
	{
		final Exception e = (Exception)v;
		byte[] data = null;
		try
		{
			HRDBMSWorker.logger.debug("", (Exception)v);
			if (e.getMessage() == null)
			{
				final StringWriter sw = new StringWriter();
				final PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				data = sw.toString().getBytes(StandardCharsets.UTF_8);
			}
			else
			{
				data = e.getMessage().getBytes(StandardCharsets.UTF_8);
			}
		}
		catch (final Exception f)
		{
		}

		final int dataLen = data.length;
		final int recLen = 9 + dataLen;
		final ByteBuffer bb = ByteBuffer.allocate(recLen + 4);
		bb.position(0);
		bb.putInt(recLen);
		bb.putInt(1);
		bb.put((byte)10);
		bb.putInt(dataLen);
		bb.put(data);
		return bb.array();
	}

	@Override
	public void add(final Operator op) throws Exception
	{
		if (child == null)
		{
			child = op;
			op.registerParent(this);
			cols2Types = op.getCols2Types();
			cols2Pos = op.getCols2Pos();
			pos2Col = op.getPos2Col();
		}
		else
		{
			throw new Exception("NetworkSendOperator only supports 1 child");
		}
	}

	public void addConnection(final int fromNode, final Socket sock)
	{
	}

	@Override
	public ArrayList<Operator> children()
	{
		final ArrayList<Operator> retval = new ArrayList<Operator>(1);
		retval.add(child);
		return retval;
	}

	public void clearParent()
	{
		parent = null;
	}

	@Override
	public NetworkSendOperator clone()
	{
		final NetworkSendOperator retval = new NetworkSendOperator(node, meta);
		retval.numParents = numParents;
		retval.numpSet = numpSet;
		retval.cardSet = cardSet;
		return retval;
	}

	@Override
	public void close() throws Exception
	{
		if (out != null)
		{
			out.close();
		}

		if (sock != null)
		{
			sock.close();
		}

		cols2Pos = null;
		cols2Types = null;
		pos2Col = null;
	}

	@Override
	public int getChildPos()
	{
		return 0;
	}

	@Override
	public HashMap<String, Integer> getCols2Pos()
	{
		return cols2Pos;
	}

	@Override
	public HashMap<String, String> getCols2Types()
	{
		return cols2Types;
	}

	@Override
	public MetaData getMeta()
	{
		return meta;
	}

	@Override
	public int getNode()
	{
		return node;
	}

	@Override
	public TreeMap<Integer, String> getPos2Col()
	{
		return pos2Col;
	}

	@Override
	public ArrayList<String> getReferences()
	{
		return null;
	}

	public boolean hasAllConnections()
	{
		return false;
	}

	@Override
	public Object next(final Operator op) throws Exception
	{
		throw new Exception("Unsupported operation");
	}

	@Override
	public void nextAll(final Operator op)
	{
	}

	public boolean notStarted()
	{
		return !started;
	}

	@Override
	public long numRecsReceived()
	{
		return received.get();
	}

	@Override
	public Operator parent()
	{
		return parent;
	}

	@Override
	public boolean receivedDEM()
	{
		return demReceived;
	}

	@Override
	public void registerParent(final Operator op) throws Exception
	{
		if (parent == null)
		{
			parent = op;
		}
		else
		{
			throw new Exception("NetworkSendOperator only supports 1 parent.");
		}
	}

	@Override
	public void removeChild(final Operator op)
	{
		child = null;
		op.removeParent(this);
	}

	@Override
	public void removeParent(final Operator op)
	{
		parent = null;
	}

	@Override
	public void reset() throws Exception
	{
		HRDBMSWorker.logger.error("NetworkSendOperator does not support reset()");
		throw new Exception("NetworkSendOperator does not support reset()");
	}

	@Override
	public void serialize(final OutputStream out, final IdentityHashMap<Object, Long> prev) throws Exception
	{
		final Long id = prev.get(this);
		if (id != null)
		{
			OperatorUtils.serializeReference(id, out);
			return;
		}

		OperatorUtils.writeType(HrdbmsType.NSO, out);
		prev.put(this, OperatorUtils.writeID(out));
		// recreate meta
		child.serialize(out, prev);
		// parent.serialize(out, prev);
		OperatorUtils.serializeOperator(parent, out, prev);
		OperatorUtils.serializeStringHM(cols2Types, out, prev);
		OperatorUtils.serializeStringIntHM(cols2Pos, out, prev);
		OperatorUtils.serializeTM(pos2Col, out, prev);
		OperatorUtils.writeInt(node, out);
		OperatorUtils.writeInt(numParents, out);
		OperatorUtils.writeBool(started, out);
		OperatorUtils.writeBool(numpSet, out);
		OperatorUtils.writeBool(cardSet, out);
	}

	public void serialize(final OutputStream out, final IdentityHashMap<Object, Long> prev, final boolean flag) throws Exception
	{
	}

	public boolean setCard()
	{
		if (cardSet)
		{
			return false;
		}

		cardSet = true;
		return true;
	}

	@Override
	public void setChildPos(final int pos)
	{
	}

	@Override
	public void setNode(final int node)
	{
		this.node = node;
	}

	public boolean setNumParents()
	{
		if (numpSet)
		{
			return false;
		}

		numpSet = true;
		return true;
	}

	@Override
	public void setPlan(final Plan plan)
	{
	}

	public void setSocket(final Socket sock) throws Exception
	{
		try
		{
			this.sock = sock;
			out = new BufferedOutputStream(sock.getOutputStream());
		}
		catch (final Exception e)
		{
			HRDBMSWorker.logger.error("", e);
			throw e;
		}
	}

	@Override
	public synchronized void start() throws Exception
	{
		final CompressedOutputStream compOut = new CompressedOutputStream(out);
		try
		{
			started = true;
			// child.start();
			Object o = child.next(this);
			if (o instanceof DataEndMarker)
			{
				demReceived = true;
			}
			else
			{
				received.getAndIncrement();
			}
			while (!(o instanceof DataEndMarker))
			{
				final byte[] obj = toBytes(o);
				try
				{
					compOut.write(obj);
				}
				catch (final Exception e)
				{
					HRDBMSWorker.logger.debug("Error writing to " + sock.getRemoteSocketAddress());
					throw e;
				}
				if (o instanceof Exception)
				{
					HRDBMSWorker.logger.debug("", (Exception)o);
					throw (Exception)o;
				}
				o = child.next(this);
				if (o instanceof DataEndMarker)
				{
					demReceived = true;
				}
				else
				{
					received.getAndIncrement();
				}
			}

			final byte[] obj = toBytes(o);
			compOut.write(obj);
			compOut.flush();
			// HRDBMSWorker.logger.debug("Wrote " + count + " rows");
			child.close();
			// Thread.sleep(60 * 1000); WHY was this here?
		}
		catch (final Throwable e)
		{
			HRDBMSWorker.logger.debug("", e);
			try
			{
				final byte[] obj = toBytes(e);
				compOut.write(obj);
				compOut.flush();
				compOut.close();
				child.nextAll(this);
				child.close();
			}
			catch (final Throwable f)
			{
				HRDBMSWorker.logger.debug("", f);
				try
				{
					compOut.close();
					child.nextAll(this);
					child.close();
				}
				catch (final Throwable g)
				{
					HRDBMSWorker.logger.debug("", g);
					try
					{
						child.nextAll(this);
						child.close();
					}
					catch (final Throwable h)
					{
						HRDBMSWorker.logger.debug("", h);
					}
				}
			}
		}
	}

	public void startChildren() throws Exception
	{
		child.start();
	}

	@Override
	public String toString()
	{
		return "NetworkSendOperator(" + node + ")";
	}

	protected byte[] toBytes(final Object v) throws Exception
	{
		ArrayList<byte[]> bytes = null;
		ArrayList<Object> val = null;
		if (v instanceof ArrayList)
		{
			val = (ArrayList<Object>)v;
			if (val.size() == 0)
			{
				throw new Exception("Empty ArrayList in toBytes()");
			}
		}
		else if (v instanceof Exception)
		{
			return toBytesException(v);
		}
		else
		{
			return toBytesDEM();
		}

		int size = val.size() + 8;
		final byte[] header = new byte[size];
		int i = 8;
		int z = 0;
		int limit = val.size();
		// for (final Object o : val)
		while (z < limit)
		{
			final Object o = val.get(z++);
			if (o instanceof Long)
			{
				header[i] = (byte)0;
				size += 8;
			}
			else if (o instanceof Integer)
			{
				header[i] = (byte)1;
				size += 4;
			}
			else if (o instanceof Double)
			{
				header[i] = (byte)2;
				size += 8;
			}
			else if (o instanceof MyDate)
			{
				header[i] = (byte)3;
				size += 4;
			}
			else if (o instanceof String)
			{
				header[i] = (byte)4;
				// byte[] b = ((String)o).getBytes("UTF-8");
				final byte[] ba = new byte[((String)o).length() << 2];
				final char[] value = (char[])unsafe.getObject(o, offset);
				final int blen = ((sun.nio.cs.ArrayEncoder)ce).encode(value, 0, value.length, ba);
				final byte[] b = Arrays.copyOf(ba, blen);
				size += (4 + b.length);
				if (bytes == null)
				{
					bytes = new ArrayList<byte[]>();
					bytes.add(b);
				}
				else
				{
					bytes.add(b);
				}
			}
			else
			{
				throw new Exception("Unknown type " + o.getClass() + " in toBytes()");
			}

			i++;
		}

		final byte[] retval = new byte[size];
		// System.out.println("In toBytes(), row has " + val.size() +
		// " columns, object occupies " + size + " bytes");
		System.arraycopy(header, 0, retval, 0, header.length);
		i = 8;
		final ByteBuffer retvalBB = ByteBuffer.wrap(retval);
		retvalBB.putInt(size - 4);
		retvalBB.putInt(val.size());
		retvalBB.position(header.length);
		int x = 0;
		z = 0;
		limit = val.size();
		// for (final Object o : val)
		while (z < limit)
		{
			final Object o = val.get(z++);
			if (retval[i] == 0)
			{
				retvalBB.putLong((Long)o);
			}
			else if (retval[i] == 1)
			{
				retvalBB.putInt((Integer)o);
			}
			else if (retval[i] == 2)
			{
				retvalBB.putDouble((Double)o);
			}
			else if (retval[i] == 3)
			{
				retvalBB.putInt(((MyDate)o).getTime());
			}
			else if (retval[i] == 4)
			{
				final byte[] temp = bytes.get(x);
				x++;
				retvalBB.putInt(temp.length);
				retvalBB.put(temp);
			}

			i++;
		}

		return retval;
	}
}
