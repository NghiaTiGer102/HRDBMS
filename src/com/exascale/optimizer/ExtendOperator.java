package com.exascale.optimizer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import com.exascale.gpu.Kernel;
import com.exascale.gpu.Rootbeer;
import com.exascale.managers.HRDBMSWorker;
import com.exascale.managers.ResourceManager;
import com.exascale.misc.BufferedLinkedBlockingQueue;
import com.exascale.misc.DataEndMarker;
import com.exascale.misc.FastStringTokenizer;
import com.exascale.misc.HrdbmsType;
import com.exascale.tables.Plan;
import com.exascale.threads.HRDBMSThread;
import com.exascale.threads.ThreadPoolThread;

public final class ExtendOperator implements Operator, Serializable
{
	private static sun.misc.Unsafe unsafe;

	static
	{
		try
		{
			final Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			unsafe = (sun.misc.Unsafe)f.get(null);
		}
		catch (final Exception e)
		{
			unsafe = null;
		}
	}

	private Operator child;
	private Operator parent;
	private HashMap<String, String> cols2Types;
	private HashMap<String, Integer> cols2Pos;
	private TreeMap<Integer, String> pos2Col;
	private String prefix;
	private transient final MetaData meta;
	private String name;
	private int node;
	private FastStringTokenizer tokens;
	private ArrayDeque<String> master;
	private transient BufferedLinkedBlockingQueue queue;
	private transient volatile ArrayList<Integer> poses;

	private volatile boolean startDone = false;

	private transient volatile ArrayList<Double> literals;
	private transient AtomicLong received;
	private transient volatile boolean demReceived;
	private boolean singleThreaded = false;

	public ExtendOperator(final String prefix, final String name, final MetaData meta)
	{
		this.prefix = prefix;
		this.meta = meta;
		this.name = name;
		this.tokens = new FastStringTokenizer(prefix, ",", false);
		this.master = new ArrayDeque<String>();
		for (final String token : tokens.allTokens())
		{
			master.push(token);
		}
		received = new AtomicLong(0);
	}

	public static ExtendOperator deserialize(final InputStream in, final HashMap<Long, Object> prev) throws Exception
	{
		final ExtendOperator value = (ExtendOperator)unsafe.allocateInstance(ExtendOperator.class);
		prev.put(OperatorUtils.readLong(in), value);
		value.child = OperatorUtils.deserializeOperator(in, prev);
		value.parent = OperatorUtils.deserializeOperator(in, prev);
		value.cols2Types = OperatorUtils.deserializeStringHM(in, prev);
		value.cols2Pos = OperatorUtils.deserializeStringIntHM(in, prev);
		value.pos2Col = OperatorUtils.deserializeTM(in, prev);
		value.prefix = OperatorUtils.readString(in, prev);
		value.name = OperatorUtils.readString(in, prev);
		value.node = OperatorUtils.readInt(in);
		value.tokens = OperatorUtils.deserializeFST(in, prev);
		value.master = OperatorUtils.deserializeADS(in, prev);
		value.startDone = OperatorUtils.readBool(in);
		value.singleThreaded = OperatorUtils.readBool(in);
		value.received = new AtomicLong(0);
		value.demReceived = false;
		return value;
	}

	@Override
	public void add(final Operator op) throws Exception
	{
		if (child == null)
		{
			child = op;
			child.registerParent(this);
			if (child.getCols2Types() != null)
			{
				cols2Types = (HashMap<String, String>)child.getCols2Types().clone();
				cols2Types.put(name, "FLOAT");
				cols2Pos = (HashMap<String, Integer>)child.getCols2Pos().clone();
				cols2Pos.put(name, cols2Pos.size());
				pos2Col = (TreeMap<Integer, String>)child.getPos2Col().clone();
				pos2Col.put(pos2Col.size(), name);
			}
		}
		else
		{
			throw new Exception("ExtendOperator only supports 1 child.");
		}
	}

	@Override
	public ArrayList<Operator> children()
	{
		final ArrayList<Operator> retval = new ArrayList<Operator>(1);
		retval.add(child);
		return retval;
	}

	@Override
	public ExtendOperator clone()
	{
		final ExtendOperator retval = new ExtendOperator(prefix, name, meta);
		retval.node = node;
		retval.tokens = tokens.clone();
		retval.singleThreaded = singleThreaded;
		return retval;
	}

	@Override
	public void close() throws Exception
	{
		child.close();

		if (queue != null)
		{
			queue.close();
		}

		master = null;
		queue = null;
		poses = null;
		cols2Pos = null;
		cols2Types = null;
		cols2Pos = null;
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

	public String getOutputCol()
	{
		return name;
	}

	@Override
	public TreeMap<Integer, String> getPos2Col()
	{
		return pos2Col;
	}

	public String getPrefix()
	{
		return prefix;
	}

	@Override
	public ArrayList<String> getReferences()
	{
		final ArrayList<String> retval = new ArrayList<String>();
		final FastStringTokenizer tokens = new FastStringTokenizer(prefix, ",", false);
		while (tokens.hasMoreTokens())
		{
			String temp = tokens.nextToken();
			if (Character.isLetter(temp.charAt(0)) || (temp.charAt(0) == '_') || temp.charAt(0) == '.')
			{
				if (cols2Pos == null)
				{
					retval.add(temp);
					continue;
				}

				final Integer x = cols2Pos.get(temp);
				if (x == null)
				{
					int count = 0;
					if (temp.startsWith("."))
					{
						temp = temp.substring(1);
					}
					for (String col : cols2Pos.keySet())
					{
						final String origCol = col;
						if (col.contains("."))
						{
							col = col.substring(col.indexOf('.') + 1);
							if (col.equals(temp))
							{
								count++;
								if (count == 1)
								{
									temp = origCol;
								}
								else
								{
									return null;
								}
							}
						}
					}
				}

				retval.add(temp);
			}
		}

		return retval;
	}

	public boolean isSingleThreaded()
	{
		return singleThreaded;
	}

	// @?Parallel
	@Override
	public Object next(final Operator op) throws Exception
	{
		Object o;
		o = queue.take();

		if (o instanceof DataEndMarker)
		{
			o = queue.peek();
			if (o == null)
			{
				queue.put(new DataEndMarker());
				return new DataEndMarker();
			}
			else
			{
				queue.put(new DataEndMarker());
				return o;
			}
		}

		if (o instanceof Exception)
		{
			throw (Exception)o;
		}
		return o;
	}

	@Override
	public void nextAll(final Operator op) throws Exception
	{
		child.nextAll(op);
		Object o = next(op);
		while (!(o instanceof DataEndMarker) && !(o instanceof Exception))
		{
			o = next(op);
		}
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
			throw new Exception("ExtendOperator only supports 1 parent.");
		}
	}

	@Override
	public void removeChild(final Operator op)
	{
		if (op == child)
		{
			child = null;
			op.removeParent(this);
		}
	}

	@Override
	public void removeParent(final Operator op)
	{
		parent = null;
	}

	@Override
	public void reset() throws Exception
	{
		throw new UnsupportedOperationException();
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

		OperatorUtils.writeType(HrdbmsType.EXTEND, out);
		prev.put(this, OperatorUtils.writeID(out));
		child.serialize(out, prev);
		parent.serialize(out, prev);
		OperatorUtils.serializeStringHM(cols2Types, out, prev);
		OperatorUtils.serializeStringIntHM(cols2Pos, out, prev);
		OperatorUtils.serializeTM(pos2Col, out, prev);
		OperatorUtils.writeString(prefix, out, prev);
		OperatorUtils.writeString(name, out, prev);
		OperatorUtils.writeInt(node, out);
		OperatorUtils.serializeFST(tokens, out, prev);
		OperatorUtils.serializeADS(master, out, prev);
		OperatorUtils.writeBool(startDone, out);
		OperatorUtils.writeBool(singleThreaded, out);
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

	@Override
	public void setPlan(final Plan plan)
	{
	}

	public void setSingleThreaded()
	{
		singleThreaded = true;
	}

	@Override
	public void start() throws Exception
	{
		startDone = true;
		child.start();
		queue = new BufferedLinkedBlockingQueue(ResourceManager.QUEUE_SIZE);
		if (!singleThreaded && ResourceManager.GPU)
		{
			new GPUThread(true).start();
		}
		else
		{
			if (singleThreaded)
			{
				new NonGPUThread().run();
			}
			else
			{
				new NonGPUThread().start();
			}
		}
	}

	@Override
	public String toString()
	{
		return "ExtendOperator: " + name + "=" + prefix;
	}

	private Object notCached(String temp, final ArrayList<Object> row, final ArrayList<Integer> p)
	{
		Integer x = cols2Pos.get(temp);
		if (x == null)
		{
			int count = 0;
			if (temp.startsWith("."))
			{
				temp = temp.substring(1);
			}
			for (String col : cols2Pos.keySet())
			{
				final String origCol = col;
				if (col.contains("."))
				{
					col = col.substring(col.indexOf('.') + 1);
					if (col.equals(temp))
					{
						count++;
						if (count == 1)
						{
							x = cols2Pos.get(origCol);
						}
						else
						{
							queue.put(new Exception("Column " + temp + " is ambiguous"));
						}
					}
				}
			}
		}
		p.add(x);
		return row.get(x);
	}

	private final Double parsePrefixDouble(final ArrayList<Object> row, final ArrayDeque<Object> execStack)
	{
		ArrayList<Integer> p = null;
		ArrayList<Double> ds = null;
		final ArrayDeque<String> parseStack = master.clone();
		int i = 0;
		int j = 0;
		// System.out.println("Starting parse stack = " + parseStack);

		int y = parseStack.size();
		while (y > 0)
		{
			y--;
			// System.out.println("Exec stack = " + execStack);
			final String temp = parseStack.pop();
			// System.out.println("We popped " + temp);
			if (temp.equals("*"))
			{
				final Double lhs = (Double)execStack.pop();
				final Double rhs = (Double)execStack.pop();
				// System.out.println("Arguments are " + lhs + " and " + rhs);
				execStack.push(lhs * rhs);

			}
			else if (temp.equals("-"))
			{
				final Double lhs = (Double)execStack.pop();
				final Double rhs = (Double)execStack.pop();
				// System.out.println("Arguments are " + lhs + " and " + rhs);
				execStack.push(lhs - rhs);
			}
			else if (temp.equals("+"))
			{
				final Double lhs = (Double)execStack.pop();
				final Double rhs = (Double)execStack.pop();
				// System.out.println("Arguments are " + lhs + " and " + rhs);
				execStack.push(lhs + rhs);
			}
			else if (temp.equals("/"))
			{
				final Double lhs = (Double)execStack.pop();
				final Double rhs = (Double)execStack.pop();
				// System.out.println("Arguments are " + lhs + " and " + rhs);
				execStack.push(lhs / rhs);
			}
			else
			{
				try
				{
					if (Character.isLetter(temp.charAt(0)) || (temp.charAt(0) == '_') || temp.charAt(0) == '.')
					{
						Object field = null;
						Integer x;
						if (poses != null)
						{
							x = poses.get(i);
							i++;
							field = row.get(x);
						}
						else
						{
							if (p == null)
							{
								p = new ArrayList<Integer>();
							}

							field = notCached(temp, row, p);
						}

						if (field instanceof Long)
						{
							execStack.push(new Double(((Long)field).longValue()));
						}
						else if (field instanceof Integer)
						{
							execStack.push(new Double(((Integer)field).intValue()));
						}
						else if (field instanceof Double)
						{
							execStack.push(field);
						}
						else
						{
							queue.put(new Exception("Unknown type in ExtendOperator: " + field.getClass()));
							execStack.clear();
							return 0D;
						}
					}
					else
					{
						if (literals != null)
						{
							execStack.push(literals.get(j++));
						}
						else
						{
							if (ds == null)
							{
								ds = new ArrayList<Double>();
							}
							final double d = Double.parseDouble(temp);
							// System.out.println("Parsed a literal numeric
							// value and got "
							// + d);
							execStack.push(d);
							ds.add(d);
						}
					}
				}
				catch (final Exception e)
				{
					HRDBMSWorker.logger.error("", e);
					try
					{
						queue.put(e);
					}
					catch (final Exception f)
					{
					}
					execStack.clear();
					return 0D;
				}
			}
		}

		if (poses == null)
		{
			poses = p;
		}

		if (literals == null)
		{
			literals = ds;
		}

		final Double retval = (Double)execStack.pop();
		// System.out.println("Going to return " + retval);
		return retval;
	}

	public static final class ExtendKernel implements Kernel
	{
		public ArrayList<Object> row;
		public ArrayDeque master;
		public ArrayList<Integer> poses;
		public ArrayList<Double> calced;

		public ExtendKernel(final ArrayList<Object> row, final ArrayDeque master, final ArrayList<Integer> poses, final ArrayList<Double> calced)
		{
			this.row = row;
			this.master = master;
			this.poses = poses;
			this.calced = calced;
		}

		public ArrayList<Object> getRow()
		{
			return row;
		}
	}

	private final class GPUThread extends ThreadPoolThread
	{
		private final boolean sendDEM;

		public GPUThread(final boolean sendDEM)
		{
			this.sendDEM = sendDEM;
		}

		@Override
		public void run()
		{
			List<Kernel> jobs = null;
			if (ResourceManager.GPU)
			{
				jobs = new ArrayList<Kernel>(ResourceManager.CUDA_SIZE);
			}
			final ArrayList<Double> calced = new ArrayList<Double>();
			final ArrayDeque<Object> execStack = new ArrayDeque<Object>();
			int i = 0;
			while (true)
			{
				Object o = null;
				try
				{
					o = child.next(ExtendOperator.this);
					if (o instanceof DataEndMarker)
					{
						demReceived = true;
					}
					else
					{
						received.getAndIncrement();
					}
				}
				catch (final Exception e)
				{
					HRDBMSWorker.logger.error("", e);
					try
					{
						queue.put(e);
					}
					catch (final Exception f)
					{
					}
					return;
				}

				if (o instanceof DataEndMarker)
				{
					if (i > 0)
					{
						final Rootbeer rootbeer = new Rootbeer();
						try
						{
							rootbeer.runAll(jobs);
							i = 0;

							for (final Kernel k : jobs)
							{
								((ExtendKernel)k).getRow().add(calced.get(i));
								queue.put(((ExtendKernel)k).getRow());
								i++;
							}
						}
						catch (final Exception e)
						{
							try
							{
								queue.put(e);
							}
							catch (final Exception f)
							{
							}
							return;
						}
					}

					if (sendDEM)
					{
						try
						{
							queue.put(o);
							return;
						}
						catch (final Exception e)
						{
							try
							{
								queue.put(e);
							}
							catch (final Exception f)
							{
							}
							return;
						}
					}
					else
					{
						return;
					}
				}

				final ArrayList<Object> row = (ArrayList<Object>)o;
				if (poses == null || !ResourceManager.GPU)
				{
					final Double ppd = parsePrefixDouble(row, execStack);
					row.add(ppd);
					try
					{
						queue.put(row);
					}
					catch (final Exception e)
					{
						try
						{
							queue.put(e);
						}
						catch (final Exception f)
						{
						}
						return;
					}
				}
				else
				{
					jobs.add(new ExtendKernel(row, master, poses, calced));
					i++;

					if (i == ResourceManager.CUDA_SIZE)
					{
						final Rootbeer rootbeer = new Rootbeer();
						try
						{
							rootbeer.runAll(jobs);
							i = 0;

							for (final Kernel k : jobs)
							{
								final Double ppd = calced.get(i);
								((ExtendKernel)k).getRow().add(ppd);
								queue.put(((ExtendKernel)k).getRow());
								i++;
							}
						}
						catch (final Exception e)
						{
							try
							{
								queue.put(e);
							}
							catch (final Exception f)
							{
							}
							return;
						}

						i = 0;
						jobs.clear();
						calced.clear();
					}
				}
			}
		}
	}

	private class NonGPUThread extends HRDBMSThread
	{
		@Override
		public void run()
		{
			if (singleThreaded)
			{
				new GPUThread(false).run();
				queue.put(new DataEndMarker());
				return;
			}

			final ArrayList<GPUThread> threads = new ArrayList<GPUThread>();
			int z = 0;
			int limit = Runtime.getRuntime().availableProcessors();
			final int max = Integer.parseInt(HRDBMSWorker.getHParms().getProperty("extend_max_par"));
			if (limit > max)
			{
				limit = max;
			}

			while (z < limit)
			{
				final GPUThread thread = new GPUThread(false);
				thread.start();
				threads.add(thread);
				z++;
			}

			z = 0;
			while (z < limit)
			{
				try
				{
					threads.get(z++).join();
				}
				catch (final Exception e)
				{
				}
			}

			queue.put(new DataEndMarker());
		}
	}
}
