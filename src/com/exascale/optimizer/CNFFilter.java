package com.exascale.optimizer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import com.exascale.managers.HRDBMSWorker;
import com.exascale.misc.*;

/** Filter based on a conjunctive normal form expression */
public class CNFFilter implements Serializable
{
	private static sun.misc.Unsafe unsafe;
	private static final int HASH_THRESHOLD = 10;
	private static int pbpeVer;
	private static boolean isV7;

	static
	{
		try
		{
			final Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			unsafe = (sun.misc.Unsafe)f.get(null);
			pbpeVer = Integer.parseInt(HRDBMSWorker.getHParms().getProperty("pbpe_version"));
			isV7 = (pbpeVer == 7);
		}
		catch (final Exception e)
		{
			unsafe = null;
		}
	}

	private ArrayList<ArrayList<Filter>> filters = new ArrayList<ArrayList<Filter>>();

	private transient MetaData meta;

	private volatile HashMap<String, Integer> cols2Pos;

	private transient ArrayList<Object> partHash;

	private transient ArrayList<Filter> rangeFilters;

	private volatile HashSet<HashMap<Filter, Filter>> hshm = null;

	private DataEndMarker hshmLock = new DataEndMarker();

	private volatile HashSet<String> references;
	private volatile IdentityHashMap<ArrayList<Filter>, Boolean> hashEligibleCache;
	private volatile IdentityHashMap<ArrayList<Filter>, Integer> hashColPos;
	private volatile IdentityHashMap<ArrayList<Filter>, HJOMultiHashMap<Integer, Filter>> hashMapCache;

	private transient HashSet<Filter> falseForPage;

	public CNFFilter()
	{
	}

	public CNFFilter(final HashSet<HashMap<Filter, Filter>> clause, final HashMap<String, Integer> cols2Pos)
	{
		this.cols2Pos = cols2Pos;
		this.setHSHM(clause);
	}

	public CNFFilter(final HashSet<HashMap<Filter, Filter>> clause, final MetaData meta, final HashMap<String, Integer> cols2Pos, final HashMap<String, Double> generated, final Operator tree) throws Exception
	{
		this.meta = meta;
		this.cols2Pos = cols2Pos;
		this.setHSHM(clause);
	}

	public CNFFilter(final HashSet<HashMap<Filter, Filter>> clause, final MetaData meta, final HashMap<String, Integer> cols2Pos, final Operator tree) throws Exception
	{
		this.meta = meta;
		this.cols2Pos = cols2Pos;
		this.setHSHM(clause);
	}

	public CNFFilter(final HashSet<HashMap<Filter, Filter>> clause, final MetaData meta, final HashMap<String, Integer> cols2Pos, final RootOperator op) throws Exception
	{
		this(clause, meta, cols2Pos, op.getGenerated(), op);
	}

	public static CNFFilter deserializeKnown(final InputStream in, final HashMap<Long, Object> prev) throws Exception
	{
		final CNFFilter value = (CNFFilter)unsafe.allocateInstance(CNFFilter.class);
		prev.put(OperatorUtils.readLong(in), value);
		value.filters = OperatorUtils.deserializeALALF(in, prev);
		value.cols2Pos = OperatorUtils.deserializeStringIntHM(in, prev);
		value.hshmLock = new DataEndMarker();
		return value;
	}

	public static void quicksort(final ArrayList main, final ArrayList<Double> scores)
	{
		quicksort(main, scores, 0, scores.size() - 1);
	}

	// quicksort a[left] to a[right]
	public static void quicksort(final ArrayList<Object> a, final ArrayList<Double> scores, final int left, final int right)
	{
		if (right <= left)
		{
			return;
		}
		final int i = partition(a, scores, left, right);
		quicksort(a, scores, left, i - 1);
		quicksort(a, scores, i + 1, right);
	}

	public static void reverseQuicksort(final ArrayList main, final ArrayList<Double> scores)
	{
		reverseQuicksort(main, scores, 0, scores.size() - 1);
	}

	// quicksort a[left] to a[right]
	public static void reverseQuicksort(final ArrayList<Object> a, final ArrayList<Double> scores, final int left, final int right)
	{
		if (right <= left)
		{
			return;
		}
		final int i = reversePartition(a, scores, left, right);
		reverseQuicksort(a, scores, left, i - 1);
		reverseQuicksort(a, scores, i + 1, right);
	}

	private static boolean areEquivalent(final String l, final String r)
	{
		String lhs = l;
		String rhs = r;

		if (lhs.contains("."))
		{
			lhs = lhs.substring(lhs.indexOf('.') + 1);
		}

		if (rhs.contains("."))
		{
			rhs = rhs.substring(rhs.indexOf('.') + 1);
		}

		return lhs.equals(rhs);
	}

	private static HJOMultiHashMap<Integer, Filter> computeHashedFilters(final ArrayList<Filter> filter)
	{
		final HJOMultiHashMap<Integer, Filter> retval = new HJOMultiHashMap<Integer, Filter>();
		for (final Filter f : filter)
		{
			if (f.leftIsColumn())
			{
				final Object o = f.rightLiteral();
				final int code = o.hashCode();
				retval.multiPut(code, f);
			}
			else
			{
				final Object o = f.leftLiteral();
				final int code = o.hashCode();
				retval.multiPut(code, f);
			}
		}

		return retval;
	}

	// exchange a[i] and a[j]
	private static void exch(final ArrayList<Object> a, final ArrayList<Double> scores, final int i, final int j)
	{
		final Object swap1 = a.get(i);
		final Object swap2 = a.get(j);
		final Double swap3 = scores.get(i);
		final Double swap4 = scores.get(j);
		// a[i] = a[j];
		a.remove(i);
		a.add(i, swap2);
		scores.remove(i);
		scores.add(i, swap4);
		// a[j] = swap;
		a.remove(j);
		a.add(j, swap1);
		scores.remove(j);
		scores.add(j, swap3);
	}

	private static long hash(final Object key) throws Exception
	{
		long eHash;
		if (key == null)
		{
			eHash = 0;
		}
		else
		{
			if (key instanceof ArrayList)
			{
				final byte[] data = toBytesForHash((ArrayList<Object>)key);
				eHash = MurmurHash.hash64(data, data.length);
			}
			else
			{
				final byte[] data = key.toString().getBytes(StandardCharsets.UTF_8);
				eHash = MurmurHash.hash64(data, data.length);
			}
		}

		return eHash;
	}

	// is x < y ?
	private static boolean less(final Double x, final Double y)
	{
		return x.compareTo(y) < 0;
	}

	private static boolean more(final Double x, final Double y)
	{
		return x.compareTo(y) > 0;
	}

	// partition a[left] to a[right], assumes left < right
	private static int partition(final ArrayList<Object> a, final ArrayList<Double> scores, final int left, final int right)
	{
		int i = left - 1;
		int j = right;
		while (true)
		{
			while (less(scores.get(++i), scores.get(right)))
			{
				; // a[right] acts as sentinel
			}
			while (less(scores.get(right), scores.get(--j)))
			{
				if (j == left)
				{
					break; // don't go out-of-bounds
				}
			}
			if (i >= j)
			{
				break; // check if pointers cross
			}
			exch(a, scores, i, j); // swap two elements into place
		}
		exch(a, scores, i, right); // swap with partition element
		return i;
	}

	// partition a[left] to a[right], assumes left < right
	private static int reversePartition(final ArrayList<Object> a, final ArrayList<Double> scores, final int left, final int right)
	{
		int i = left - 1;
		int j = right;
		while (true)
		{
			while (more(scores.get(++i), scores.get(right)))
			{
				; // a[right] acts as sentinel
			}
			while (more(scores.get(right), scores.get(--j)))
			{
				if (j == left)
				{
					break; // don't go out-of-bounds
				}
			}
			if (i >= j)
			{
				break; // check if pointers cross
			}
			exch(a, scores, i, j); // swap two elements into place
		}
		exch(a, scores, i, right); // swap with partition element
		return i;
	}

	private static byte[] toBytesForHash(final ArrayList<Object> key)
	{
		final StringBuilder sb = new StringBuilder();
		for (final Object o : key)
		{
			if (o instanceof Double)
			{
				final DecimalFormat df = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
				df.setMaximumFractionDigits(340); // 340 =
													// DecimalFormat.DOUBLE_FRACTION_DIGITS

				sb.append(df.format(o));
				sb.append((char)0);
			}
			else if (o instanceof Number)
			{
				sb.append(o);
				sb.append((char)0);
			}
			else
			{
				sb.append(o.toString());
				sb.append((char)0);
			}
		}

		final int z = sb.length();
		final byte[] retval = new byte[z];
		int i = 0;
		while (i < z)
		{
			retval[i] = (byte)sb.charAt(i);
			i++;
		}

		return retval;
	}

	@Override
	public CNFFilter clone()
	{
		final CNFFilter retval = new CNFFilter();
		retval.filters = cloneFilters();
		retval.meta = meta;
		retval.cols2Pos = cols2Pos;
		return retval;
	}

	// private static final byte[] toBytes(Object v) throws Exception
	// {
	// ArrayList<byte[]> bytes = null;
	// ArrayList<Object> val;
	// if (v instanceof ArrayList)
	// {
	// val = (ArrayList<Object>)v;
	// }
	// else
	// {
	// final byte[] retval = new byte[9];
	// retval[0] = 0;
	// retval[1] = 0;
	// retval[2] = 0;
	// retval[3] = 5;
	// retval[4] = 0;
	// retval[5] = 0;
	// retval[6] = 0;
	// retval[7] = 1;
	// retval[8] = 5;
	// return retval;
	// }

	// int size = val.size() + 8;
	// final byte[] header = new byte[size];
	// int i = 8;
	// int z = 0;
	// int limit = val.size();
	// // for (final Object o : val)
	// while (z < limit)
	// {
	// Object o = val.get(z++);
	// if (o instanceof Long)
	// {
	// header[i] = (byte)0;
	// size += 8;
	// }
	// else if (o instanceof Integer)
	// {
	// header[i] = (byte)1;
	// size += 4;
	// }
	// else if (o instanceof Double)
	// {
	// header[i] = (byte)2;
	// size += 8;
	// }
	// else if (o instanceof MyDate)
	// {
	// header[i] = (byte)3;
	// size += 4;
	// }
	// else if (o instanceof String)
	// {
	// header[i] = (byte)4;
	// byte[] b = ((String)o).getBytes(StandardCharsets.UTF_8);
	// size += (4 + b.length);
	//
	// if (bytes == null)
	// {
	// bytes = new ArrayList<byte[]>();
	// bytes.add(b);
	// }
	// else
	// {
	// bytes.add(b);
	// }
	// }
	// // else if (o instanceof AtomicLong)
	// {
	// header[i] = (byte)6;
	// size += 8;
	// }
	// else if (o instanceof AtomicBigDecimal)
	// {
	// header[i] = (byte)7;
	// size += 8;
	// }
	// else if (o instanceof ArrayList)
	// {
	// if (((ArrayList)o).size() != 0)
	// {
	// Exception e = new Exception("Non-zero size ArrayList in toBytes()");
	// HRDBMSWorker.logger.error("Non-zero size ArrayList in toBytes()", e);
	// throw e;
	// }
	// header[i] = (byte)8;
	// }
	// else
	// {
	// HRDBMSWorker.logger.error("Unknown type " + o.getClass() + " in
	// toBytes()");
	// HRDBMSWorker.logger.error(o);
	// throw new Exception("Unknown type " + o.getClass() + " in toBytes()");
	// }

	// i++;
	// }

	// final byte[] retval = new byte[size];
	// // System.out.println("In toBytes(), row has " + val.size() +
	// " columns, object occupies " + size + " bytes");
	// System.arraycopy(header, 0, retval, 0, header.length);
	// i = 8;
	// final ByteBuffer retvalBB = ByteBuffer.wrap(retval);
	// retvalBB.putInt(size - 4);
	// retvalBB.putInt(val.size());
	// retvalBB.position(header.length);
	// int x = 0;
	// z = 0;
	// limit = val.size();
	// // for (final Object o : val)
	// while (z < limit)
	// {
	// Object o = val.get(z++);
	// if (retval[i] == 0)
	// {
	// retvalBB.putLong((Long)o);
	// }
	// else if (retval[i] == 1)
	// {
	// retvalBB.putInt((Integer)o);
	// }
	// else if (retval[i] == 2)
	// {
	// retvalBB.putDouble((Double)o);
	// }
	// else if (retval[i] == 3)
	// {
	// retvalBB.putInt(((MyDate)o).getTime());
	// }
	// else if (retval[i] == 4)
	// {
	// byte[] temp = bytes.get(x);
	// x++;
	// retvalBB.putInt(temp.length);
	// retvalBB.put(temp);
	// }
	// else if (retval[i] == 6)
	// {
	// retvalBB.putLong(((AtomicLong)o).get());
	// }
	// else if (retval[i] == 7)
	// {
	// retvalBB.putDouble(((AtomicBigDecimal)o).get().doubleValue());
	// }
	// else if (retval[i] == 8)
	// {
	// }
	//
	// i++;
	// }

	// return retval;
	// }

	public ArrayList<ArrayList<Filter>> cloneFilters()
	{
		final ArrayList<ArrayList<Filter>> retval = new ArrayList<ArrayList<Filter>>(filters.size());
		for (final ArrayList<Filter> list : filters)
		{
			retval.add((ArrayList<Filter>)list.clone());
		}

		return retval;
	}

	public ArrayList<ArrayList<Filter>> getALAL()
	{
		return filters;
	}

	public HashSet<Filter> getFalseResults()
	{
		return falseForPage;
	}

	public HashSet<HashMap<Filter, Filter>> getHSHM()
	{
		if (hshm == null)
		{
			synchronized (hshmLock)
			{
				if (hshm == null)
				{
					final HashSet<HashMap<Filter, Filter>> hshmTemp = new HashSet<HashMap<Filter, Filter>>();
					// release
					for (final ArrayList<Filter> filter : filters)
					{
						final HashMap<Filter, Filter> hm = new HashMap<Filter, Filter>();
						for (final Filter f : filter)
						{
							hm.put(f, f);
						}

						hshmTemp.add(hm);
					}

					hshm = hshmTemp;
				}
			}
		}

		return hshm;
	}

	public long getPartitionHash() throws Exception
	{
		return 0x7FFFFFFFFFFFFFFFL & hash(partHash);
	}

	public ArrayList<Filter> getRangeFilters()
	{
		return rangeFilters;
	}

	public ArrayList<String> getReferences()
	{
		final ArrayList<String> retval = new ArrayList<String>(filters.size());
		for (final ArrayList<Filter> f : filters)
		{
			for (final Filter filter : f)
			{
				if (filter.leftIsColumn())
				{
					if (!retval.contains(filter.leftColumn()))
					{
						retval.add(filter.leftColumn());
					}
				}

				if (filter.rightIsColumn())
				{
					if (!retval.contains(filter.rightColumn()))
					{
						retval.add(filter.rightColumn());
					}
				}
			}
		}

		return retval;
	}

	public HashSet<String> getReferencesHash()
	{
		if (references == null)
		{
			final HashSet<String> retval = new HashSet<String>(filters.size());
			for (final ArrayList<Filter> f : filters)
			{
				for (final Filter filter : f)
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

			references = retval;
		}

		return references;
	}

	public boolean hashFiltersPartitions(final ArrayList<String> hashCols)
	{
		partHash = null;
		// true if CNFFilter has an equality op (compared to literal) for each
		// col in hashCols that is not ored with anything else
		boolean retval;
		for (final String hashCol : hashCols)
		{
			retval = false;
			for (final ArrayList<Filter> filter : filters)
			{
				if (filter.size() == 1)
				{
					final Filter f = filter.get(0);
					if (f.op().equals("E"))
					{
						if (f.leftIsColumn() && !f.rightIsColumn())
						{
							if (areEquivalent(f.leftColumn(), hashCol))
							{
								if (partHash == null)
								{
									partHash = new ArrayList<Object>();
								}
								retval = true;
								partHash.add(f.rightLiteral());
								break;
							}
						}

						if (!f.leftIsColumn() && f.rightIsColumn())
						{
							if (areEquivalent(f.rightColumn(), hashCol))
							{
								if (partHash == null)
								{
									partHash = new ArrayList<Object>();
								}
								retval = true;
								partHash.add(f.leftLiteral());
								break;
							}
						}
					}
				}
			}

			if (!retval)
			{
				return false;
			}
		}

		return true;
	}

	// @Parallel
	public boolean passes(final ArrayList<Object> row) throws Exception
	{
		int z = 0;
		final int limit = filters.size();
		// for (final ArrayList<Filter> filter : filters)
		if (isV7 && falseForPage != null)
		{
			boolean retval = true;
			while (z < limit)
			{
				final ArrayList<Filter> filter = filters.get(z++);
				if (!passesOredCondition(filter, row))
				{
					retval = false;
				}
			}

			return retval;
		}
		else
		{
			while (z < limit)
			{
				final ArrayList<Filter> filter = filters.get(z++);
				if (!passesOredCondition(filter, row))
				{
					return false;
				}
			}

			return true;
		}
	}

	// @Parallel
	public boolean passes(final ArrayList<Object> lRow, final ArrayList<Object> rRow) throws Exception
	{
		int z = 0;
		final int limit = filters.size();
		// for (final ArrayList<Filter> filter : filters)

		while (z < limit)
		{
			final ArrayList<Filter> filter = filters.get(z++);
			if (!passesOredCondition(filter, lRow, rRow))
			{
				return false;
			}
		}

		return true;
	}

	public boolean rangeFiltersPartitions(final String col)
	{
		rangeFilters = null;
		boolean retval = false;
		// true if CNFFilter has a L, LE, E, G, or GE op (compared to literal)
		// on col that is not ored with anything else
		for (final ArrayList<Filter> filter : filters)
		{
			if (filter.size() == 1)
			{
				final Filter f = filter.get(0);
				if (f.op().equals("L") || f.op().equals("LE") || f.op().equals("E") || f.op().equals("G") || f.op().equals("GE"))
				{
					if (f.leftIsColumn() && !f.rightIsColumn())
					{
						if (areEquivalent(f.leftColumn(), col))
						{
							if (rangeFilters == null)
							{
								rangeFilters = new ArrayList<Filter>();
							}

							rangeFilters.add(f);
							retval = true;
						}
					}

					if (!f.leftIsColumn() && f.rightIsColumn())
					{
						if (areEquivalent(f.rightColumn(), col))
						{
							if (rangeFilters == null)
							{
								rangeFilters = new ArrayList<Filter>();
							}

							rangeFilters.add(f);
							retval = true;
						}
					}
				}
			}
		}

		return retval;
	}

	public void reset()
	{
		falseForPage = new HashSet<Filter>();
		for (final ArrayList<Filter> filter : filters)
		{
			for (final Filter f : filter)
			{
				falseForPage.add(f);
			}
		}
	}

	public void serialize(final OutputStream out, final IdentityHashMap<Object, Long> prev) throws Exception
	{
		final Long id = prev.get(this);
		if (id != null)
		{
			OperatorUtils.serializeReference(id, out);
			return;
		}

		OperatorUtils.writeType(HrdbmsType.CNF, out);
		prev.put(this, OperatorUtils.writeID(out));
		OperatorUtils.serializeALALF(filters, out, prev);
		OperatorUtils.serializeStringIntHM(cols2Pos, out, prev);
	}

	public void setHSHM(final HashSet<HashMap<Filter, Filter>> hshm)
	{
		filters = new ArrayList<>(hshm.size());
		for (final HashMap<Filter, Filter> hm : hshm)
		{
			final ArrayList<Filter> ors = new ArrayList<Filter>(hm.keySet());
			filters.add(ors);
			synchronized (hshmLock)
			{
				this.hshm = hshm;
			}
		}
	}

	@Override
	public String toString()
	{
		if (!(this instanceof NullCNFFilter))
		{
			return filters.toString();
		}

		return "NullCNFFilter";
	}

	public void updateCols2Pos(final HashMap<String, Integer> cols2Pos)
	{
		this.cols2Pos = cols2Pos;
	}

	private int computeHashColPos(final ArrayList<Filter> filter)
	{
		final Filter f = filter.get(0);
		if (f.leftIsColumn())
		{
			return cols2Pos.get(f.leftColumn());
		}

		return cols2Pos.get(f.rightColumn());
	}

	private boolean hashEligible(final ArrayList<Filter> filter)
	{
		if (hashEligibleCache == null)
		{
			hashEligibleCache = new IdentityHashMap<ArrayList<Filter>, Boolean>();
		}

		Boolean retval = null;
		synchronized (hashEligibleCache)
		{
			retval = hashEligibleCache.get(filter);
		}
		if (retval != null)
		{
			return retval;
		}

		// are all disjuncts col/literal and is the col always the same?
		boolean ret = true;
		String col = null;
		for (final Filter f : filter)
		{
			if (f.leftIsColumn() && !f.rightIsColumn())
			{
				final String c = f.leftColumn();
				if (col == null)
				{
					col = c;
				}
				else if (!c.equals(col))
				{
					ret = false;
					break;
				}
			}
			else if (!f.leftIsColumn() && f.rightIsColumn())
			{
				final String c = f.rightColumn();
				if (col == null)
				{
					col = c;
				}
				else if (!c.equals(col))
				{
					ret = false;
					break;
				}
			}
		}

		synchronized (hashEligibleCache)
		{
			hashEligibleCache.put(filter, ret);
		}
		return ret;
	}

	private final boolean passesOredCondition(final ArrayList<Filter> filter, final ArrayList<Object> row) throws Exception
	{
		try
		{
			if (filter.size() >= HASH_THRESHOLD)
			{
				if (hashEligible(filter))
				{
					if (isV7)
					{
						falseForPage.clear();
					}
					return passesOredConditionHash(filter, row);
				}
			}
			int z = 0;
			final int limit = filter.size();
			// for (final Filter f : filter)
			if (isV7 && falseForPage != null)
			{
				boolean retval = false;
				while (z < limit)
				{
					final Filter f = filter.get(z++);
					if (f.passes(row, cols2Pos))
					{
						retval = true;
						falseForPage.remove(f);
					}
				}

				return retval;
			}
			else
			{
				while (z < limit)
				{
					final Filter f = filter.get(z++);
					if (f.passes(row, cols2Pos))
					{
						return true;
					}
				}
			}
		}
		catch (final Exception e)
		{
			HRDBMSWorker.logger.error("", e);
			throw e;
		}

		return false;
	}

	private final boolean passesOredCondition(final ArrayList<Filter> filter, final ArrayList<Object> lRow, final ArrayList<Object> rRow) throws Exception
	{
		try
		{
			if (filter.size() >= HASH_THRESHOLD)
			{
				if (hashEligible(filter))
				{
					return passesOredConditionHash(filter, lRow, rRow);
				}
			}
			int z = 0;
			final int limit = filter.size();
			// for (final Filter f : filter)
			while (z < limit)
			{
				final Filter f = filter.get(z++);
				if (f.passes(lRow, rRow, cols2Pos))
				{
					return true;
				}
			}
		}
		catch (final Exception e)
		{
			HRDBMSWorker.logger.error("", e);
			throw e;
		}

		return false;
	}

	private boolean passesOredConditionHash(final ArrayList<Filter> filter, final ArrayList<Object> row) throws Exception
	{
		if (hashColPos == null)
		{
			hashColPos = new IdentityHashMap<ArrayList<Filter>, Integer>();
		}
		Integer colPos = null;
		synchronized (hashColPos)
		{
			colPos = hashColPos.get(filter);
		}
		if (colPos == null)
		{
			colPos = computeHashColPos(filter);
			synchronized (hashColPos)
			{
				hashColPos.put(filter, colPos);
			}
		}

		final Object obj = row.get(colPos);
		final int code = obj.hashCode();
		if (hashMapCache == null)
		{
			hashMapCache = new IdentityHashMap<ArrayList<Filter>, HJOMultiHashMap<Integer, Filter>>();
		}
		HJOMultiHashMap<Integer, Filter> hashedFilters = null;
		synchronized (hashMapCache)
		{
			hashedFilters = hashMapCache.get(filter);
		}
		if (hashedFilters == null)
		{
			hashedFilters = computeHashedFilters(filter);
			synchronized (hashMapCache)
			{
				hashMapCache.put(filter, hashedFilters);
			}
		}

		final List<Filter> validFilters = hashedFilters.get(code);
		for (final Filter f : validFilters)
		{
			if (f.passes(row, cols2Pos))
			{
				return true;
			}
		}

		return false;
	}

	private boolean passesOredConditionHash(final ArrayList<Filter> filter, final ArrayList<Object> lRow, final ArrayList<Object> rRow) throws Exception
	{
		if (hashColPos == null)
		{
			hashColPos = new IdentityHashMap<ArrayList<Filter>, Integer>();
		}
		Integer colPos = null;
		synchronized (hashColPos)
		{
			colPos = hashColPos.get(filter);
		}
		if (colPos == null)
		{
			colPos = computeHashColPos(filter);
			synchronized (hashColPos)
			{
				hashColPos.put(filter, colPos);
			}
		}

		Object obj = null;
		if (colPos < lRow.size())
		{
			obj = lRow.get(colPos);
		}
		else
		{
			obj = rRow.get(colPos - lRow.size());
		}
		final int code = obj.hashCode();
		if (hashMapCache == null)
		{
			hashMapCache = new IdentityHashMap<ArrayList<Filter>, HJOMultiHashMap<Integer, Filter>>();
		}
		HJOMultiHashMap<Integer, Filter> hashedFilters = null;
		synchronized (hashMapCache)
		{
			hashedFilters = hashMapCache.get(filter);
		}
		if (hashedFilters == null)
		{
			hashedFilters = computeHashedFilters(filter);
			synchronized (hashMapCache)
			{
				hashMapCache.put(filter, hashedFilters);
			}
		}

		final List<Filter> validFilters = hashedFilters.get(code);
		for (final Filter f : validFilters)
		{
			if (f.passes(lRow, rRow, cols2Pos))
			{
				return true;
			}
		}

		return false;
	}
	
	public static String isTrue(HashSet<HashMap<Filter, Filter>> hshm)
	{
		if (hshm.isEmpty())
		{
			return null;
		}
		
		StringBuilder sb = new StringBuilder();
		boolean outerFirst = true;
		for (HashMap<Filter, Filter> hm : hshm)
		{
			if (outerFirst)
			{
				outerFirst = false;
			}
			else
			{
				sb.append(" AND ");
			}
			
			sb.append("(");
			boolean first = true;
			for (Filter f : hm.keySet())
			{
				if (first)
				{
					first = false;
				}
				else
				{
					sb.append(" OR ");
				}
				
				if (f.leftIsColumn())
				{
					String col = f.leftColumn();
					if (col.contains("."))
					{
						col = col.substring(col.indexOf('.') + 1);
					}
					
					sb.append(col);
				}
				else
				{
					if (f.leftIsDate())
					{
						MyDate o = f.getLeftDate();
						sb.append("DATE('" + o + "')");
					}
					else if (f.leftIsNumber())
					{
						sb.append(f.getLeftNumber());
					}
					else
					{
						sb.append("'" + f.getLeftString() + "'");
					}
				}
				
				String op = f.op();
				if (op.equals("E"))
				{
					sb.append(" = ");
				}
				else if (op.equals("NE"))
				{
					sb.append(" <> ");
				}
				else if (op.equals("G"))
				{
					sb.append(" > ");
				}
				else if (op.equals("GE"))
				{
					sb.append(" >= ");
				}
				else if (op.equals("L"))
				{
					sb.append(" < ");
				}
				else if (op.equals("LE"))
				{
					sb.append(" <= ");
				}
				else if (op.equals("LI"))
				{
					sb.append(" LIKE ");
				}
				else
				{
					sb.append(" NOT LIKE ");
				}
				
				if (f.rightIsColumn())
				{
					String col = f.rightColumn();
					if (col.contains("."))
					{
						col = col.substring(col.indexOf('.') + 1);
					}
					
					sb.append(col);
				}
				else
				{
					if (f.rightIsDate())
					{
						MyDate o = f.getRightDate();
						sb.append("DATE('" + o + "')");
					}
					else if (f.rightIsNumber())
					{
						sb.append(f.getRightNumber());
					}
					else
					{
						sb.append("'" + f.getRightString() + "'");
					}
				}
			}
			
			sb.append(")");
		}
		
		return sb.toString();
	}
	
	public static String notTrue(ArrayList<HashSet<HashMap<Filter, Filter>>> hshms)
	{
		if (hshms.isEmpty())
		{
			return null;
		}
		
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (HashSet<HashMap<Filter, Filter>> hshm : hshms)
		{
			if (first)
			{
				first = false;
			}
			else
			{
				sb.append(" AND ");
			}
			
			sb.append(" NOT (");
			sb.append(isTrue(hshm));
			sb.append(")");
		}
		
		return sb.toString();
	}
}
