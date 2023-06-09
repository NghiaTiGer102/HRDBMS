package com.exascale.optimizer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.TreeMap;
import com.exascale.managers.HRDBMSWorker;
import com.exascale.misc.DataEndMarker;
import com.exascale.misc.HrdbmsType;
import com.exascale.tables.Plan;

public final class SemiJoinOperator implements Operator, Serializable
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

	private ArrayList<Operator> children = new ArrayList<Operator>(2);

	private Operator parent;

	private HashMap<String, String> cols2Types;

	private HashMap<String, Integer> cols2Pos;
	private TreeMap<Integer, String> pos2Col;
	private MetaData meta;
	private ArrayList<String> cols;
	private volatile ArrayList<Integer> poses;
	private int childPos = -1;
	private HashSet<HashMap<Filter, Filter>> f = null;
	private int node;
	private boolean indexAccess = false;
	private ArrayList<Index> dynamicIndexes;
	private long rightChildCard = 16;
	private boolean alreadySorted = false;
	private boolean cardSet = false;
	public transient Operator dynamicOp;
	private long leftChildCard = 16;
	private long txnum;

	public SemiJoinOperator(final ArrayList<String> cols, final MetaData meta)
	{
		this.cols = cols;
		this.meta = meta;
	}

	public SemiJoinOperator(final HashSet<HashMap<Filter, Filter>> f, final MetaData meta)
	{
		this.f = f;
		this.meta = meta;
		this.cols = new ArrayList<String>(0);
	}

	public SemiJoinOperator(final String col, final MetaData meta)
	{
		this.cols = new ArrayList<String>(1);
		this.cols.add(col);
		this.meta = meta;
	}

	private SemiJoinOperator(final ArrayList<String> cols, final HashSet<HashMap<Filter, Filter>> f, final MetaData meta)
	{
		this.f = f;
		this.cols = cols;
		this.meta = meta;
	}

	public static SemiJoinOperator deserialize(final InputStream in, final HashMap<Long, Object> prev) throws Exception
	{
		final SemiJoinOperator value = (SemiJoinOperator)unsafe.allocateInstance(SemiJoinOperator.class);
		prev.put(OperatorUtils.readLong(in), value);
		value.children = OperatorUtils.deserializeALOp(in, prev);
		value.parent = OperatorUtils.deserializeOperator(in, prev);
		value.cols2Types = OperatorUtils.deserializeStringHM(in, prev);
		value.cols2Pos = OperatorUtils.deserializeStringIntHM(in, prev);
		value.pos2Col = OperatorUtils.deserializeTM(in, prev);
		value.meta = new MetaData();
		value.cols = OperatorUtils.deserializeALS(in, prev);
		value.poses = OperatorUtils.deserializeALI(in, prev);
		value.childPos = OperatorUtils.readInt(in);
		value.f = OperatorUtils.deserializeHSHM(in, prev);
		value.node = OperatorUtils.readInt(in);
		value.indexAccess = OperatorUtils.readBool(in);
		value.dynamicIndexes = OperatorUtils.deserializeALIndx(in, prev);
		value.rightChildCard = OperatorUtils.readLong(in);
		value.alreadySorted = OperatorUtils.readBool(in);
		value.cardSet = OperatorUtils.readBool(in);
		value.leftChildCard = OperatorUtils.readLong(in);
		value.txnum = OperatorUtils.readLong(in);
		return value;
	}

	@Override
	public void add(final Operator op) throws Exception
	{
		if (children.size() < 2)
		{
			if (childPos == -1)
			{
				children.add(op);
			}
			else
			{
				children.add(childPos, op);
				childPos = -1;
			}
			op.registerParent(this);

			if (children.size() == 2 && children.get(0).getCols2Types() != null && children.get(1).getCols2Types() != null)
			{
				cols2Types = children.get(0).getCols2Types();
				cols2Pos = children.get(0).getCols2Pos();
				pos2Col = children.get(0).getPos2Col();

				poses = new ArrayList<Integer>(cols.size());
				for (final String col : cols)
				{
					poses.add(cols2Pos.get(col));
				}
			}
		}
		else
		{
			throw new Exception("SemiJoinOperator only supports 2 children");
		}
	}

	public void alreadySorted()
	{
		alreadySorted = true;
	}

	@Override
	public ArrayList<Operator> children()
	{
		return children;
	}

	@Override
	public SemiJoinOperator clone()
	{
		final SemiJoinOperator retval = new SemiJoinOperator(cols, f, meta);
		retval.node = node;
		retval.indexAccess = indexAccess;
		retval.dynamicIndexes = dynamicIndexes;
		retval.alreadySorted = alreadySorted;
		retval.rightChildCard = rightChildCard;
		retval.cardSet = cardSet;
		retval.leftChildCard = leftChildCard;
		retval.txnum = txnum;
		return retval;
	}

	@Override
	public void close() throws Exception
	{
		dynamicOp.close();
		cols2Pos = null;
		cols2Types = null;
		pos2Col = null;
		cols = null;
		poses = null;
		f = null;
		dynamicIndexes = null;
	}

	@Override
	public int getChildPos()
	{
		return childPos;
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

	public HashSet<HashMap<Filter, Filter>> getHSHM() throws Exception
	{
		if (f != null)
		{
			return f;
		}

		final HashSet<HashMap<Filter, Filter>> retval = new HashSet<HashMap<Filter, Filter>>();
		int i = 0;
		for (final String col : children.get(1).getPos2Col().values())
		{
			Filter filter = null;
			try
			{
				filter = new Filter(cols.get(i), "E", col);
			}
			catch (final Exception e)
			{
				HRDBMSWorker.logger.error("", e);
				throw e;
			}
			final HashMap<Filter, Filter> hm = new HashMap<Filter, Filter>();
			hm.put(filter, filter);
			retval.add(hm);
			i++;
		}

		return retval;
	}

	public boolean getIndexAccess()
	{
		return indexAccess;
	}

	public ArrayList<String> getJoinForChild(final Operator op)
	{
		if (cols.size() > 0)
		{
			if (op.getCols2Pos().keySet().containsAll(cols))
			{
				return new ArrayList<String>(cols);
			}
			else
			{
				return new ArrayList<String>(op.getCols2Pos().keySet());
			}
		}

		Filter x = null;
		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("E"))
					{
						if (filter.leftIsColumn() && filter.rightIsColumn())
						{
							x = filter;
						}
					}

					break;
				}
			}

			if (x != null)
			{
				break;
			}
		}

		if (x == null)
		{
			return null;
		}

		if (op.getCols2Pos().keySet().contains(x.leftColumn()))
		{
			final ArrayList<String> retval = new ArrayList<String>(1);
			retval.add(x.leftColumn());
			return retval;
		}

		final ArrayList<String> retval = new ArrayList<String>(1);
		retval.add(x.rightColumn());
		return retval;
	}

	public ArrayList<String> getLefts()
	{
		if (cols.size() > 0)
		{
			return cols;
		}

		final ArrayList<String> retval = new ArrayList<String>(f.size());
		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("E"))
					{
						if (children.get(0).getCols2Pos().keySet().contains(filter.leftColumn()))
						{
							retval.add(filter.leftColumn());
						}
						else
						{
							retval.add(filter.rightColumn());
						}
					}
				}
			}
		}

		return retval;
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
		try
		{
			final HashSet<HashMap<Filter, Filter>> hshm = getHSHM();
			final ArrayList<String> retval = new ArrayList<String>(cols);
			for (final HashMap<Filter, Filter> filters : hshm)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.leftIsColumn())
					{
						retval.add(filter.leftColumn());
					}

					if (filter.rightIsColumn())
					{
						retval.add(filter.rightColumn());
					}
				}
			}
			return retval;
		}
		catch (final Exception e)
		{
			return null;
		}
	}

	public ArrayList<String> getRights()
	{
		if (cols.size() > 0)
		{
			final ArrayList<String> retval = new ArrayList<String>(children.get(1).getCols2Pos().keySet());
			return retval;
		}

		final ArrayList<String> retval = new ArrayList<String>(f.size());
		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("E"))
					{
						if (children.get(1).getCols2Pos().keySet().contains(filter.leftColumn()))
						{
							retval.add(filter.leftColumn());
						}
						else
						{
							retval.add(filter.rightColumn());
						}
					}
				}
			}
		}

		return retval;
	}

	@Override
	public Object next(final Operator op) throws Exception
	{
		return dynamicOp.next(this);
	}

	@Override
	public void nextAll(final Operator op) throws Exception
	{
		dynamicOp.nextAll(this);
		Object o = next(op);
		while (!(o instanceof DataEndMarker) && !(o instanceof Exception))
		{
			o = next(op);
		}
	}

	@Override
	public long numRecsReceived()
	{
		return dynamicOp.numRecsReceived();
	}

	@Override
	public Operator parent()
	{
		return parent;
	}

	@Override
	public boolean receivedDEM()
	{
		return dynamicOp.receivedDEM();
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
			throw new Exception("SemiJoinOperator can only have 1 parent.");
		}
	}

	@Override
	public void removeChild(final Operator op)
	{
		childPos = children.indexOf(op);
		if (childPos == -1)
		{
			final Exception e = new Exception();
			HRDBMSWorker.logger.error("Removing a non-existent child!", e);
		}
		children.remove(op);
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
		HRDBMSWorker.logger.error("SemiJoinOperator cannot be reset");
		throw new Exception("SemiJoinOperator cannot be reset");
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

		OperatorUtils.writeType(HrdbmsType.SJO, out);
		prev.put(this, OperatorUtils.writeID(out));
		OperatorUtils.serializeALOp(children, out, prev);
		parent.serialize(out, prev);
		OperatorUtils.serializeStringHM(cols2Types, out, prev);
		OperatorUtils.serializeStringIntHM(cols2Pos, out, prev);
		OperatorUtils.serializeTM(pos2Col, out, prev);
		// create new meta on receive side
		OperatorUtils.serializeALS(cols, out, prev);
		OperatorUtils.serializeALI(poses, out, prev);
		OperatorUtils.writeInt(childPos, out);
		OperatorUtils.serializeHSHM(f, out, prev);
		OperatorUtils.writeInt(node, out);
		OperatorUtils.writeBool(indexAccess, out);
		OperatorUtils.serializeALIndx(dynamicIndexes, out, prev);
		OperatorUtils.writeLong(rightChildCard, out);
		OperatorUtils.writeBool(alreadySorted, out);
		OperatorUtils.writeBool(cardSet, out);
		OperatorUtils.writeLong(leftChildCard, out);
		OperatorUtils.writeLong(txnum, out);
	}

	@Override
	public void setChildPos(final int pos)
	{
		childPos = pos;
	}

	public void setDynamicIndex(final ArrayList<Index> indexes)
	{
		indexAccess = true;
		this.dynamicIndexes = indexes;
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

	public boolean setRightChildCard(final long card, final long card2)
	{
		if (cardSet)
		{
			return false;
		}

		cardSet = true;
		rightChildCard = card;
		leftChildCard = card2;
		return true;
	}

	public void setTXNum(final long txnum)
	{
		this.txnum = txnum;
	}

	public ArrayList<String> sortKeys()
	{
		if (cols.size() > 0)
		{
			return null;
		}

		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("G") || filter.op().equals("GE") || filter.op().equals("L") || filter.op().equals("LE"))
					{
						if (filter.leftIsColumn() && filter.rightIsColumn())
						{
							String vStr;
							if (filter.op().equals("G") || filter.op().equals("GE"))
							{
								vStr = filter.rightColumn();
								// vBool = true;
								// System.out.println("VBool set to true");
							}
							else
							{
								vStr = filter.rightColumn();
								// vBool = false;
								// System.out.println("VBool set to false");
							}

							try
							{
								children.get(1).getCols2Pos().get(vStr);
							}
							catch (final Exception e)
							{
								vStr = filter.leftColumn();
								// vBool = !vBool;
								// pos =
								// children.get(1).getCols2Pos().get(vStr);
							}

							final ArrayList<String> retval = new ArrayList<String>(1);
							retval.add(vStr);
							return retval;
						}
					}
					else if (filter.op().equals("E") && filter.leftIsColumn() && filter.rightIsColumn())
					{
						return null;
					}
				}
			}
		}

		return null;
	}

	public ArrayList<Boolean> sortOrders()
	{
		if (cols.size() > 0)
		{
			return null;
		}

		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("G") || filter.op().equals("GE") || filter.op().equals("L") || filter.op().equals("LE"))
					{
						if (filter.leftIsColumn() && filter.rightIsColumn())
						{
							String vStr;
							boolean vBool;
							if (filter.op().equals("G") || filter.op().equals("GE"))
							{
								vStr = filter.rightColumn();
								vBool = true;
								// System.out.println("VBool set to true");
							}
							else
							{
								vStr = filter.rightColumn();
								vBool = false;
								// System.out.println("VBool set to false");
							}

							try
							{
								children.get(1).getCols2Pos().get(vStr);
							}
							catch (final Exception e)
							{
								// vStr = filter.leftColumn();
								vBool = !vBool;
								// pos =
								// children.get(1).getCols2Pos().get(vStr);
							}

							final ArrayList<Boolean> retval = new ArrayList<Boolean>(1);
							retval.add(vBool);
							return retval;
						}
					}
					else if (filter.op().equals("E") && filter.leftIsColumn() && filter.rightIsColumn())
					{
						return null;
					}
				}
			}
		}

		return null;
	}

	@Override
	public void start() throws Exception
	{
		final boolean usesHash = usesHash();
		final boolean usesSort = usesSort();
		final HashSet<HashMap<Filter, Filter>> temp = getHSHM();

		if (usesHash)
		{
			// HJO w/ existence + filter
			final ArrayList<String> lefts = this.getJoinForChild(children.get(0));
			final ArrayList<String> rights = this.getJoinForChild(children.get(1));
			dynamicOp = new HashJoinOperator(lefts.get(0), rights.get(0), meta);
			((HashJoinOperator)dynamicOp).setTXNum(txnum);
			if (lefts.size() > 1)
			{
				int i = 1;
				while (i < lefts.size())
				{
					((HashJoinOperator)dynamicOp).addJoinCondition(lefts.get(i), rights.get(i));
					i++;
				}
			}
			final Operator left = children.get(0);
			final Operator right = children.get(1);
			removeChild(left);
			removeChild(right);
			if (left instanceof TableScanOperator)
			{
				((TableScanOperator)left).rebuild();
			}

			if (right instanceof TableScanOperator)
			{
				((TableScanOperator)right).rebuild();
			}
			dynamicOp.add(left);
			dynamicOp.add(right);
			if (left instanceof TableScanOperator)
			{
				((TableScanOperator)left).setCNFForParent(dynamicOp, ((TableScanOperator)left).getCNFForParent(this));
			}
			if (right instanceof TableScanOperator)
			{
				((TableScanOperator)right).setCNFForParent(dynamicOp, ((TableScanOperator)right).getCNFForParent(this));
			}
			((HashJoinOperator)dynamicOp).setCNF(temp);
			((HashJoinOperator)dynamicOp).setRightChildCard(rightChildCard, leftChildCard);
			((HashJoinOperator)dynamicOp).setSemi();
			dynamicOp.start();
		}
		else if (usesSort)
		{
			// not implemented - add sort to the below
			// prod w/ existence + filter + remove from left
			dynamicOp = new ProductOperator(meta);
			((ProductOperator)dynamicOp).setTXNum(txnum);
			final Operator left = children.get(0);
			final Operator right = children.get(1);
			removeChild(left);
			removeChild(right);
			if (left instanceof TableScanOperator)
			{
				((TableScanOperator)left).rebuild();
			}

			if (right instanceof TableScanOperator)
			{
				((TableScanOperator)right).rebuild();
			}
			dynamicOp.add(left);
			dynamicOp.add(right);
			if (left instanceof TableScanOperator)
			{
				((TableScanOperator)left).setCNFForParent(dynamicOp, ((TableScanOperator)left).getCNFForParent(this));
			}
			if (right instanceof TableScanOperator)
			{
				((TableScanOperator)right).setCNFForParent(dynamicOp, ((TableScanOperator)right).getCNFForParent(this));
			}
			((ProductOperator)dynamicOp).setHSHM(temp);
			((ProductOperator)dynamicOp).setRightChildCard(rightChildCard, leftChildCard);
			((ProductOperator)dynamicOp).setSemi();
			dynamicOp.start();
		}
		else
		{
			// prod w/ existence + filter + remove from left
			dynamicOp = new ProductOperator(meta);
			((ProductOperator)dynamicOp).setTXNum(txnum);
			final Operator left = children.get(0);
			final Operator right = children.get(1);
			removeChild(left);
			removeChild(right);
			if (left instanceof TableScanOperator)
			{
				((TableScanOperator)left).rebuild();
			}

			if (right instanceof TableScanOperator)
			{
				((TableScanOperator)right).rebuild();
			}
			dynamicOp.add(left);
			dynamicOp.add(right);
			if (left instanceof TableScanOperator)
			{
				((TableScanOperator)left).setCNFForParent(dynamicOp, ((TableScanOperator)left).getCNFForParent(this));
			}
			if (right instanceof TableScanOperator)
			{
				((TableScanOperator)right).setCNFForParent(dynamicOp, ((TableScanOperator)right).getCNFForParent(this));
			}
			((ProductOperator)dynamicOp).setHSHM(temp);
			((ProductOperator)dynamicOp).setRightChildCard(rightChildCard, leftChildCard);
			((ProductOperator)dynamicOp).setSemi();
			dynamicOp.start();
		}
	}

	@Override
	public String toString()
	{
		return "SemiJoinOperator";
	}

	public boolean usesHash()
	{
		if (cols.size() > 0)
		{
			return true;
		}

		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("E") && filter.leftIsColumn() && filter.rightIsColumn())
					{
						return true;
					}
				}
			}
		}

		return false;
	}

	public boolean usesSort()
	{
		if (cols.size() > 0)
		{
			return false;
		}

		boolean isSort = false;

		for (final HashMap<Filter, Filter> filters : f)
		{
			if (filters.size() == 1)
			{
				for (final Filter filter : filters.keySet())
				{
					if (filter.op().equals("G") || filter.op().equals("GE") || filter.op().equals("L") || filter.op().equals("LE"))
					{
						if (filter.leftIsColumn() && filter.rightIsColumn())
						{
							isSort = true;
						}
					}
					else if (filter.op().equals("E") && filter.leftIsColumn() && filter.rightIsColumn())
					{
						return false;
					}
				}
			}
		}

		return isSort;
	}
}
