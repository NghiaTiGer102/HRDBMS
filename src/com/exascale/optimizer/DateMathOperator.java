package com.exascale.optimizer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import com.exascale.managers.HRDBMSWorker;
import com.exascale.misc.*;
import com.exascale.tables.Plan;

public final class DateMathOperator implements Operator, Serializable
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
	private String col;
	private int type;
	private int offset;
	private String name;
	private transient final MetaData meta;
	private int node;
	private int colPos;

	private transient SimpleDateFormat sdf;

	private transient MySimpleDateFormat msdf;
	private transient AtomicLong received;
	private transient volatile boolean demReceived;

	public DateMathOperator(final String col, final int type, final int offset, final String name, final MetaData meta)
	{
		this.col = col;
		this.type = type;
		this.offset = offset;
		this.meta = meta;
		this.name = name;
		received = new AtomicLong(0);
	}

	public static DateMathOperator deserialize(final InputStream in, final HashMap<Long, Object> prev) throws Exception
	{
		final DateMathOperator value = (DateMathOperator)unsafe.allocateInstance(DateMathOperator.class);
		prev.put(OperatorUtils.readLong(in), value);
		value.child = OperatorUtils.deserializeOperator(in, prev);
		value.parent = OperatorUtils.deserializeOperator(in, prev);
		value.cols2Types = OperatorUtils.deserializeStringHM(in, prev);
		value.cols2Pos = OperatorUtils.deserializeStringIntHM(in, prev);
		value.pos2Col = OperatorUtils.deserializeTM(in, prev);
		value.col = OperatorUtils.readString(in, prev);
		value.type = OperatorUtils.readInt(in);
		value.offset = OperatorUtils.readInt(in);
		value.name = OperatorUtils.readString(in, prev);
		value.node = OperatorUtils.readInt(in);
		value.colPos = OperatorUtils.readInt(in);
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
				cols2Types.put(name, "DATE");
				cols2Pos = (HashMap<String, Integer>)child.getCols2Pos().clone();
				cols2Pos.put(name, cols2Pos.size());
				pos2Col = (TreeMap<Integer, String>)child.getPos2Col().clone();
				pos2Col.put(pos2Col.size(), name);
				colPos = cols2Pos.get(col);
				Integer colPos1 = cols2Pos.get(col);
				if (colPos1 == null)
				{
					int count = 0;
					if (col.startsWith("."))
					{
						col = col.substring(1);
						for (String col3 : cols2Pos.keySet())
						{
							final String orig = col3;
							if (col3.contains("."))
							{
								col3 = col3.substring(col3.indexOf('.') + 1);
							}

							if (col3.equals(col))
							{
								col = orig;
								count++;
								colPos1 = cols2Pos.get(orig);
							}

							if (count > 1)
							{
								throw new Exception("Ambiguous column: " + col);
							}
						}
					}
				}
			}
		}
		else
		{
			throw new Exception("DateMathOperator only supports 1 child.");
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
	public DateMathOperator clone()
	{
		try
		{
			final DateMathOperator retval = new DateMathOperator(col, type, offset, name, meta);
			retval.node = node;
			return retval;
		}
		catch (final Exception e)
		{
			return null;
		}
	}

	@Override
	public void close() throws Exception
	{
		cols2Pos = null;
		cols2Types = null;
		pos2Col = null;
		child.close();
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

	@Override
	public ArrayList<String> getReferences()
	{
		final ArrayList<String> retval = new ArrayList<String>();
		retval.add(col);
		return retval;
	}

	@Override
	public Object next(final Operator op) throws Exception
	{
		final Object o = child.next(this);
		if (o instanceof DataEndMarker)
		{
			demReceived = true;
			return o;
		}
		else
		{
			received.getAndIncrement();
		}

		if (o instanceof Exception)
		{
			throw (Exception)o;
		}

		final ArrayList<Object> row = (ArrayList<Object>)o;
		final MyDate mDate = (MyDate)row.get(colPos);
		if (type == SQLParser.TYPE_DAYS)
		{
			final Date date = sdf.parse(msdf.format(mDate));
			final GregorianCalendar cal = new GregorianCalendar();
			cal.setTime(date);
			cal.add(Calendar.DATE, offset);
			row.add(DateParser.parse(sdf.format(cal.getTime())));
		}
		else
		{
			final Exception e = new Exception("DateMathOperator doesn't support anything other than TYPE_DAYS");
			HRDBMSWorker.logger.error("DateMathOperator doesn't support anything other than TYPE_DAYS", e);
			throw e;
		}
		return row;
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
			throw new Exception("DateMathOperator only supports 1 parent.");
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
		if (sdf == null)
		{
			sdf = new SimpleDateFormat("yyyy-MM-dd");
			msdf = new MySimpleDateFormat("yyyy-MM-dd");
		}
		child.reset();
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

		OperatorUtils.writeType(HrdbmsType.DATEMATH, out);
		prev.put(this, OperatorUtils.writeID(out));
		child.serialize(out, prev);
		parent.serialize(out, prev);
		OperatorUtils.serializeStringHM(cols2Types, out, prev);
		OperatorUtils.serializeStringIntHM(cols2Pos, out, prev);
		OperatorUtils.serializeTM(pos2Col, out, prev);
		OperatorUtils.writeString(col, out, prev);
		OperatorUtils.writeInt(type, out);
		OperatorUtils.writeInt(offset, out);
		OperatorUtils.writeString(name, out, prev);
		OperatorUtils.writeInt(node, out);
		OperatorUtils.writeInt(colPos, out);
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

	@Override
	public void start() throws Exception
	{
		sdf = new SimpleDateFormat("yyyy-MM-dd");
		msdf = new MySimpleDateFormat("yyyy-MM-dd");
		child.start();
	}

	@Override
	public String toString()
	{
		return "DateMathOperator";
	}
}
