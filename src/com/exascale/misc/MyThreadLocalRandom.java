package com.exascale.misc;

import java.io.ObjectStreamField;
import java.lang.reflect.Field;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Random;
import java.util.Spliterator;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * A random number generator isolated to the current thread. Like the global
 * {@link java.util.Random} generator used by the {@link java.lang.Math} class,
 * a {@code MyThreadLocalRandom} is initialized with an internally generated
 * seed that may not otherwise be modified. When applicable, use of
 * {@code MyThreadLocalRandom} rather than shared {@code Random} objects in
 * concurrent programs will typically encounter much less overhead and
 * contention. Use of {@code MyThreadLocalRandom} is particularly appropriate
 * when multiple tasks (for example, each a {@link ForkJoinTask}) use random
 * numbers in parallel in thread pools.
 *
 * <p>
 * Usages of this class should typically be of the form:
 * {@code MyThreadLocalRandom.current().nextX(...)} (where {@code X} is
 * {@code Int}, {@code Long}, etc). When all usages are of this form, it is
 * never possible to accidently share a {@code MyThreadLocalRandom} across
 * multiple threads.
 *
 * <p>
 * This class also provides additional commonly used bounded random generation
 * methods.
 *
 * <p>
 * Instances of {@code MyThreadLocalRandom} are not cryptographically secure.
 * Consider instead using {@link java.security.SecureRandom} in
 * security-sensitive applications. Additionally, default-constructed instances
 * do not use a cryptographically random seed unless the
 * {@linkplain System#getProperty system property}
 * {@code java.util.secureRandomSeed} is set to {@code true}.
 *
 * @since 1.7
 * @author Doug Lea
 */
public class MyThreadLocalRandom extends Random
{
	/*
	 * This class implements the java.util.Random API (and subclasses Random)
	 * using a single static instance that accesses random number state held in
	 * class Thread (primarily, field threadLocalRandomSeed). In doing so, it
	 * also provides a home for managing package-private utilities that rely on
	 * exactly the same state as needed to maintain the MyThreadLocalRandom
	 * instances. We leverage the need for an initialization flag field to also
	 * use it as a "probe" -- a self-adjusting thread hash used for contention
	 * avoidance, as well as a secondary simpler (xorShift) random seed that is
	 * conservatively used to avoid otherwise surprising users by hijacking the
	 * MyThreadLocalRandom sequence. The dual use is a marriage of convenience,
	 * but is a simple and efficient way of reducing application-level overhead
	 * and footprint of most concurrent programs.
	 *
	 * Even though this class subclasses java.util.Random, it uses the same
	 * basic algorithm as java.util.SplittableRandom. (See its internal
	 * documentation for explanations, which are not repeated here.) Because
	 * MyThreadLocalRandoms are not splittable though, we use only a single
	 * 64bit gamma.
	 *
	 * Because this class is in a different package than class Thread, field
	 * access methods use Unsafe to bypass access control rules. To conform to
	 * the requirements of the Random superclass constructor, the common static
	 * MyThreadLocalRandom maintains an "initialized" field for the sake of
	 * rejecting user calls to setSeed while still allowing a call from
	 * constructor. Note that serialization is completely unnecessary because
	 * there is only a static singleton. But we generate a serial form
	 * containing "rnd" and "initialized" fields to ensure compatibility across
	 * versions.
	 *
	 * Implementations of non-core methods are mostly the same as in
	 * SplittableRandom, that were in part derived from a previous version of
	 * this class.
	 *
	 * The nextLocalGaussian ThreadLocal supports the very rarely used
	 * nextGaussian method by providing a holder for the second of a pair of
	 * them. As is true for the base class version of this method, this
	 * time/space tradeoff is probably never worthwhile, but we provide
	 * identical statistical properties.
	 */

	/** Generates per-thread initialization/probe field */
	private static final AtomicInteger probeGenerator = new AtomicInteger();

	/**
	 * The next seed for default constructors.
	 */
	private static final AtomicLong seeder = new AtomicLong(initialSeed());

	/**
	 * The seed increment
	 */
	private static final long GAMMA = 0x9e3779b97f4a7c15L;

	/**
	 * The increment for generating probe values
	 */
	private static final int PROBE_INCREMENT = 0x9e3779b9;

	/**
	 * The increment of seeder per new instance
	 */
	private static final long SEEDER_INCREMENT = 0xbb67ae8584caa73bL;

	// Constants from SplittableRandom
	private static final double DOUBLE_UNIT = 0x1.0p-53; // 1.0 / (1L << 53)

	private static final float FLOAT_UNIT = 0x1.0p-24f; // 1.0f / (1 << 24)
	/** Rarely-used holder for the second of a pair of Gaussians */
	private static final ThreadLocal<Double> nextLocalGaussian = new ThreadLocal<Double>();

	/** The common MyThreadLocalRandom */
	static final MyThreadLocalRandom instance = new MyThreadLocalRandom();

	// IllegalArgumentException messages
	static final String BadBound = "bound must be positive";

	static final String BadRange = "bound must be greater than origin";

	static final String BadSize = "size must be non-negative";

	private static final long serialVersionUID = -5851777807851030925L;

	/**
	 * @serialField rnd
	 *                  long seed for random computations
	 * @serialField initialized
	 *                  boolean always true
	 */
	private static final ObjectStreamField[] serialPersistentFields = { new ObjectStreamField("rnd", long.class), new ObjectStreamField("initialized", boolean.class), };

	// Unsafe mechanics
	private static final sun.misc.Unsafe UNSAFE;

	private static final long SEED;

	private static final long PROBE;

	private static final long SECONDARY;

	static
	{
		try
		{
			UNSAFE = getUnsafe();
			final Class<?> tk = Thread.class;
			SEED = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomSeed"));
			PROBE = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomProbe"));
			SECONDARY = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomSecondarySeed"));
		}
		catch (final Exception e)
		{
			throw new Error(e);
		}
	}

	/**
	 * Field used only during singleton initialization. True when constructor
	 * completes.
	 */
	boolean initialized;

	/** Constructor used only for static singleton */
	private MyThreadLocalRandom()
	{
		initialized = true; // false during super() call
	}

	/**
	 * Returns the current thread's {@code MyThreadLocalRandom}.
	 *
	 * @return the current thread's {@code MyThreadLocalRandom}
	 */
	public static MyThreadLocalRandom current()
	{
		if (UNSAFE.getInt(Thread.currentThread(), PROBE) == 0)
		{
			localInit();
		}
		return instance;
	}

	/**
	 * Returns the probe value for the current thread without forcing
	 * initialization. Note that invoking MyThreadLocalRandom.current() can be
	 * used to force initialization on zero return.
	 */
	static public final int getProbe()
	{
		return UNSAFE.getInt(Thread.currentThread(), PROBE);
	}

	public static sun.misc.Unsafe getUnsafe()
	{
		try
		{
			final Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (sun.misc.Unsafe)f.get(null);
		}
		catch (final Exception e)
		{
			return null;
		}
	}

	private static long initialSeed()
	{
		final String pp = java.security.AccessController.doPrivileged(new sun.security.action.GetPropertyAction("java.util.secureRandomSeed"));
		if (pp != null && pp.equalsIgnoreCase("true"))
		{
			final byte[] seedBytes = java.security.SecureRandom.getSeed(8);
			long s = (seedBytes[0]) & 0xffL;
			for (int i = 1; i < 8; ++i)
			{
				s = (s << 8) | ((seedBytes[i]) & 0xffL);
			}
			return s;
		}
		long h = 0L;
		try
		{
			final Enumeration<NetworkInterface> ifcs = NetworkInterface.getNetworkInterfaces();
			boolean retry = false; // retry once if getHardwareAddress is null
			while (ifcs.hasMoreElements())
			{
				final NetworkInterface ifc = ifcs.nextElement();
				if (!ifc.isVirtual())
				{ // skip fake addresses
					final byte[] bs = ifc.getHardwareAddress();
					if (bs != null)
					{
						final int n = bs.length;
						final int m = Math.min(n >>> 1, 4);
						for (int i = 0; i < m; ++i)
						{
							h = (h << 16) ^ (bs[i] << 8) ^ bs[n - 1 - i];
						}
						if (m < 4)
						{
							h = (h << 8) ^ bs[n - 1 - m];
						}
						h = mix64(h);
						break;
					}
					else if (!retry)
					{
						retry = true;
					}
					else
					{
						break;
					}
				}
			}
		}
		catch (final Exception ignore)
		{
		}
		return (h ^ mix64(System.currentTimeMillis()) ^ mix64(System.nanoTime()));
	}

	private static int mix32(long z)
	{
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		return (int)(((z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L) >>> 32);
	}

	private static long mix64(long z)
	{
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}

	/**
	 * Pseudo-randomly advances and records the given probe value for the given
	 * thread.
	 */
	static final int advanceProbe(int probe)
	{
		probe ^= probe << 13; // xorshift
		probe ^= probe >>> 17;
		probe ^= probe << 5;
		UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
		return probe;
	}

	/**
	 * Initialize Thread fields for the current thread. Called only when
	 * Thread.threadLocalRandomProbe is zero, indicating that a thread local
	 * seed value needs to be generated. Note that even though the
	 * initialization is purely thread-local, we need to rely on (static) atomic
	 * generators to initialize the values.
	 */
	static final void localInit()
	{
		final int p = probeGenerator.addAndGet(PROBE_INCREMENT);
		final int probe = (p == 0) ? 1 : p; // skip 0
		final long seed = mix64(seeder.getAndAdd(SEEDER_INCREMENT));
		final Thread t = Thread.currentThread();
		UNSAFE.putLong(t, SEED, seed);
		UNSAFE.putInt(t, PROBE, probe);
	}

	/**
	 * Returns the pseudo-randomly initialized or updated secondary seed.
	 */
	static final int nextSecondarySeed()
	{
		int r;
		final Thread t = Thread.currentThread();
		if ((r = UNSAFE.getInt(t, SECONDARY)) != 0)
		{
			r ^= r << 13; // xorshift
			r ^= r >>> 17;
			r ^= r << 5;
		}
		else
		{
			localInit();
			if ((r = (int)UNSAFE.getLong(t, SEED)) == 0)
			{
				r = 1; // avoid zero
			}
		}
		UNSAFE.putInt(t, SECONDARY, r);
		return r;
	}

	/**
	 * Returns an effectively unlimited stream of pseudorandom {@code double}
	 * values, each between zero (inclusive) and one (exclusive).
	 *
	 * @implNote This method is implemented to be equivalent to
	 *           {@code doubles(Long.MAX_VALUE)}.
	 *
	 * @return a stream of pseudorandom {@code double} values
	 * @since 1.8
	 */
	@Override
	public DoubleStream doubles()
	{
		return StreamSupport.doubleStream(new RandomDoublesSpliterator(0L, Long.MAX_VALUE, Double.MAX_VALUE, 0.0), false);
	}

	/**
	 * Returns an effectively unlimited stream of pseudorandom {@code double}
	 * values, each conforming to the given origin (inclusive) and bound
	 * (exclusive).
	 *
	 * @implNote This method is implemented to be equivalent to
	 *           {@code doubles(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound)}
	 *           .
	 *
	 * @param randomNumberOrigin
	 *            the origin (inclusive) of each random value
	 * @param randomNumberBound
	 *            the bound (exclusive) of each random value
	 * @return a stream of pseudorandom {@code double} values, each with the
	 *         given origin (inclusive) and bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code randomNumberOrigin} is greater than or equal to
	 *             {@code randomNumberBound}
	 * @since 1.8
	 */
	@Override
	public DoubleStream doubles(final double randomNumberOrigin, final double randomNumberBound)
	{
		if (!(randomNumberOrigin < randomNumberBound))
		{
			throw new IllegalArgumentException(BadRange);
		}
		return StreamSupport.doubleStream(new RandomDoublesSpliterator(0L, Long.MAX_VALUE, randomNumberOrigin, randomNumberBound), false);
	}

	/**
	 * Returns a stream producing the given {@code streamSize} number of
	 * pseudorandom {@code double} values, each between zero (inclusive) and one
	 * (exclusive).
	 *
	 * @param streamSize
	 *            the number of values to generate
	 * @return a stream of {@code double} values
	 * @throws IllegalArgumentException
	 *             if {@code streamSize} is less than zero
	 * @since 1.8
	 */
	@Override
	public DoubleStream doubles(final long streamSize)
	{
		if (streamSize < 0L)
		{
			throw new IllegalArgumentException(BadSize);
		}
		return StreamSupport.doubleStream(new RandomDoublesSpliterator(0L, streamSize, Double.MAX_VALUE, 0.0), false);
	}

	/**
	 * Returns a stream producing the given {@code streamSize} number of
	 * pseudorandom {@code double} values, each conforming to the given origin
	 * (inclusive) and bound (exclusive).
	 *
	 * @param streamSize
	 *            the number of values to generate
	 * @param randomNumberOrigin
	 *            the origin (inclusive) of each random value
	 * @param randomNumberBound
	 *            the bound (exclusive) of each random value
	 * @return a stream of pseudorandom {@code double} values, each with the
	 *         given origin (inclusive) and bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code streamSize} is less than zero
	 * @throws IllegalArgumentException
	 *             if {@code randomNumberOrigin} is greater than or equal to
	 *             {@code randomNumberBound}
	 * @since 1.8
	 */
	@Override
	public DoubleStream doubles(final long streamSize, final double randomNumberOrigin, final double randomNumberBound)
	{
		if (streamSize < 0L)
		{
			throw new IllegalArgumentException(BadSize);
		}
		if (!(randomNumberOrigin < randomNumberBound))
		{
			throw new IllegalArgumentException(BadRange);
		}
		return StreamSupport.doubleStream(new RandomDoublesSpliterator(0L, streamSize, randomNumberOrigin, randomNumberBound), false);
	}

	/**
	 * Returns an effectively unlimited stream of pseudorandom {@code int}
	 * values.
	 *
	 * @implNote This method is implemented to be equivalent to
	 *           {@code ints(Long.MAX_VALUE)}.
	 *
	 * @return a stream of pseudorandom {@code int} values
	 * @since 1.8
	 */
	@Override
	public IntStream ints()
	{
		return StreamSupport.intStream(new RandomIntsSpliterator(0L, Long.MAX_VALUE, Integer.MAX_VALUE, 0), false);
	}

	/**
	 * Returns an effectively unlimited stream of pseudorandom {@code int}
	 * values, each conforming to the given origin (inclusive) and bound
	 * (exclusive).
	 *
	 * @implNote This method is implemented to be equivalent to
	 *           {@code ints(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound)}
	 *           .
	 *
	 * @param randomNumberOrigin
	 *            the origin (inclusive) of each random value
	 * @param randomNumberBound
	 *            the bound (exclusive) of each random value
	 * @return a stream of pseudorandom {@code int} values, each with the given
	 *         origin (inclusive) and bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code randomNumberOrigin} is greater than or equal to
	 *             {@code randomNumberBound}
	 * @since 1.8
	 */
	@Override
	public IntStream ints(final int randomNumberOrigin, final int randomNumberBound)
	{
		if (randomNumberOrigin >= randomNumberBound)
		{
			throw new IllegalArgumentException(BadRange);
		}
		return StreamSupport.intStream(new RandomIntsSpliterator(0L, Long.MAX_VALUE, randomNumberOrigin, randomNumberBound), false);
	}

	/**
	 * Returns a stream producing the given {@code streamSize} number of
	 * pseudorandom {@code int} values.
	 *
	 * @param streamSize
	 *            the number of values to generate
	 * @return a stream of pseudorandom {@code int} values
	 * @throws IllegalArgumentException
	 *             if {@code streamSize} is less than zero
	 * @since 1.8
	 */
	@Override
	public IntStream ints(final long streamSize)
	{
		if (streamSize < 0L)
		{
			throw new IllegalArgumentException(BadSize);
		}
		return StreamSupport.intStream(new RandomIntsSpliterator(0L, streamSize, Integer.MAX_VALUE, 0), false);
	}

	// stream methods, coded in a way intended to better isolate for
	// maintenance purposes the small differences across forms.

	/**
	 * Returns a stream producing the given {@code streamSize} number of
	 * pseudorandom {@code int} values, each conforming to the given origin
	 * (inclusive) and bound (exclusive).
	 *
	 * @param streamSize
	 *            the number of values to generate
	 * @param randomNumberOrigin
	 *            the origin (inclusive) of each random value
	 * @param randomNumberBound
	 *            the bound (exclusive) of each random value
	 * @return a stream of pseudorandom {@code int} values, each with the given
	 *         origin (inclusive) and bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code streamSize} is less than zero, or
	 *             {@code randomNumberOrigin} is greater than or equal to
	 *             {@code randomNumberBound}
	 * @since 1.8
	 */
	@Override
	public IntStream ints(final long streamSize, final int randomNumberOrigin, final int randomNumberBound)
	{
		if (streamSize < 0L)
		{
			throw new IllegalArgumentException(BadSize);
		}
		if (randomNumberOrigin >= randomNumberBound)
		{
			throw new IllegalArgumentException(BadRange);
		}
		return StreamSupport.intStream(new RandomIntsSpliterator(0L, streamSize, randomNumberOrigin, randomNumberBound), false);
	}

	/**
	 * Returns an effectively unlimited stream of pseudorandom {@code long}
	 * values.
	 *
	 * @implNote This method is implemented to be equivalent to
	 *           {@code longs(Long.MAX_VALUE)}.
	 *
	 * @return a stream of pseudorandom {@code long} values
	 * @since 1.8
	 */
	@Override
	public LongStream longs()
	{
		return StreamSupport.longStream(new RandomLongsSpliterator(0L, Long.MAX_VALUE, Long.MAX_VALUE, 0L), false);
	}

	/**
	 * Returns a stream producing the given {@code streamSize} number of
	 * pseudorandom {@code long} values.
	 *
	 * @param streamSize
	 *            the number of values to generate
	 * @return a stream of pseudorandom {@code long} values
	 * @throws IllegalArgumentException
	 *             if {@code streamSize} is less than zero
	 * @since 1.8
	 */
	@Override
	public LongStream longs(final long streamSize)
	{
		if (streamSize < 0L)
		{
			throw new IllegalArgumentException(BadSize);
		}
		return StreamSupport.longStream(new RandomLongsSpliterator(0L, streamSize, Long.MAX_VALUE, 0L), false);
	}

	/**
	 * Returns an effectively unlimited stream of pseudorandom {@code long}
	 * values, each conforming to the given origin (inclusive) and bound
	 * (exclusive).
	 *
	 * @implNote This method is implemented to be equivalent to
	 *           {@code longs(Long.MAX_VALUE, randomNumberOrigin, randomNumberBound)}
	 *           .
	 *
	 * @param randomNumberOrigin
	 *            the origin (inclusive) of each random value
	 * @param randomNumberBound
	 *            the bound (exclusive) of each random value
	 * @return a stream of pseudorandom {@code long} values, each with the given
	 *         origin (inclusive) and bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code randomNumberOrigin} is greater than or equal to
	 *             {@code randomNumberBound}
	 * @since 1.8
	 */
	@Override
	public LongStream longs(final long randomNumberOrigin, final long randomNumberBound)
	{
		if (randomNumberOrigin >= randomNumberBound)
		{
			throw new IllegalArgumentException(BadRange);
		}
		return StreamSupport.longStream(new RandomLongsSpliterator(0L, Long.MAX_VALUE, randomNumberOrigin, randomNumberBound), false);
	}

	/**
	 * Returns a stream producing the given {@code streamSize} number of
	 * pseudorandom {@code long}, each conforming to the given origin
	 * (inclusive) and bound (exclusive).
	 *
	 * @param streamSize
	 *            the number of values to generate
	 * @param randomNumberOrigin
	 *            the origin (inclusive) of each random value
	 * @param randomNumberBound
	 *            the bound (exclusive) of each random value
	 * @return a stream of pseudorandom {@code long} values, each with the given
	 *         origin (inclusive) and bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code streamSize} is less than zero, or
	 *             {@code randomNumberOrigin} is greater than or equal to
	 *             {@code randomNumberBound}
	 * @since 1.8
	 */
	@Override
	public LongStream longs(final long streamSize, final long randomNumberOrigin, final long randomNumberBound)
	{
		if (streamSize < 0L)
		{
			throw new IllegalArgumentException(BadSize);
		}
		if (randomNumberOrigin >= randomNumberBound)
		{
			throw new IllegalArgumentException(BadRange);
		}
		return StreamSupport.longStream(new RandomLongsSpliterator(0L, streamSize, randomNumberOrigin, randomNumberBound), false);
	}

	/**
	 * Returns a pseudorandom {@code boolean} value.
	 *
	 * @return a pseudorandom {@code boolean} value
	 */
	@Override
	public boolean nextBoolean()
	{
		return mix32(nextSeed()) < 0;
	}

	/**
	 * Returns a pseudorandom {@code double} value between zero (inclusive) and
	 * one (exclusive).
	 *
	 * @return a pseudorandom {@code double} value between zero (inclusive) and
	 *         one (exclusive)
	 */
	@Override
	public double nextDouble()
	{
		return (mix64(nextSeed()) >>> 11) * DOUBLE_UNIT;
	}

	/**
	 * Returns a pseudorandom {@code double} value between 0.0 (inclusive) and
	 * the specified bound (exclusive).
	 *
	 * @param bound
	 *            the upper bound (exclusive). Must be positive.
	 * @return a pseudorandom {@code double} value between zero (inclusive) and
	 *         the bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code bound} is not positive
	 */
	public double nextDouble(final double bound)
	{
		if (!(bound > 0.0))
		{
			throw new IllegalArgumentException(BadBound);
		}
		final double result = (mix64(nextSeed()) >>> 11) * DOUBLE_UNIT * bound;
		return (result < bound) ? result : // correct for rounding
		Double.longBitsToDouble(Double.doubleToLongBits(bound) - 1);
	}

	/**
	 * Returns a pseudorandom {@code double} value between the specified origin
	 * (inclusive) and bound (exclusive).
	 *
	 * @param origin
	 *            the least value returned
	 * @param bound
	 *            the upper bound (exclusive)
	 * @return a pseudorandom {@code double} value between the origin
	 *         (inclusive) and the bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code origin} is greater than or equal to {@code bound}
	 */
	public double nextDouble(final double origin, final double bound)
	{
		if (!(origin < bound))
		{
			throw new IllegalArgumentException(BadRange);
		}
		return internalNextDouble(origin, bound);
	}

	/**
	 * Returns a pseudorandom {@code float} value between zero (inclusive) and
	 * one (exclusive).
	 *
	 * @return a pseudorandom {@code float} value between zero (inclusive) and
	 *         one (exclusive)
	 */
	@Override
	public float nextFloat()
	{
		return (mix32(nextSeed()) >>> 8) * FLOAT_UNIT;
	}

	@Override
	public double nextGaussian()
	{
		// Use nextLocalGaussian instead of nextGaussian field
		final Double d = nextLocalGaussian.get();
		if (d != null)
		{
			nextLocalGaussian.set(null);
			return d.doubleValue();
		}
		double v1, v2, s;
		do
		{
			v1 = 2 * nextDouble() - 1; // between -1 and 1
			v2 = 2 * nextDouble() - 1; // between -1 and 1
			s = v1 * v1 + v2 * v2;
		} while (s >= 1 || s == 0);
		final double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
		nextLocalGaussian.set(new Double(v2 * multiplier));
		return v1 * multiplier;
	}

	/**
	 * Returns a pseudorandom {@code int} value.
	 *
	 * @return a pseudorandom {@code int} value
	 */
	@Override
	public int nextInt()
	{
		return mix32(nextSeed());
	}

	/**
	 * Returns a pseudorandom {@code int} value between zero (inclusive) and the
	 * specified bound (exclusive).
	 *
	 * @param bound
	 *            the upper bound (exclusive). Must be positive.
	 * @return a pseudorandom {@code int} value between zero (inclusive) and the
	 *         bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code bound} is not positive
	 */
	@Override
	public int nextInt(final int bound)
	{
		if (bound <= 0)
		{
			throw new IllegalArgumentException(BadBound);
		}
		int r = mix32(nextSeed());
		final int m = bound - 1;
		if ((bound & m) == 0)
		{
			r &= m;
		}
		else
		{ // reject over-represented candidates
			for (int u = r >>> 1; u + m - (r = u % bound) < 0; u = mix32(nextSeed()) >>> 1)
			{
				;
			}
		}
		return r;
	}

	/**
	 * Returns a pseudorandom {@code int} value between the specified origin
	 * (inclusive) and the specified bound (exclusive).
	 *
	 * @param origin
	 *            the least value returned
	 * @param bound
	 *            the upper bound (exclusive)
	 * @return a pseudorandom {@code int} value between the origin (inclusive)
	 *         and the bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code origin} is greater than or equal to {@code bound}
	 */
	public int nextInt(final int origin, final int bound)
	{
		if (origin >= bound)
		{
			throw new IllegalArgumentException(BadRange);
		}
		return internalNextInt(origin, bound);
	}

	/**
	 * Returns a pseudorandom {@code long} value.
	 *
	 * @return a pseudorandom {@code long} value
	 */
	@Override
	public long nextLong()
	{
		return mix64(nextSeed());
	}

	// Within-package utilities

	/*
	 * Descriptions of the usages of the methods below can be found in the
	 * classes that use them. Briefly, a thread's "probe" value is a non-zero
	 * hash code that (probably) does not collide with other existing threads
	 * with respect to any power of two collision space. When it does collide,
	 * it is pseudo-randomly adjusted (using a Marsaglia XorShift). The
	 * nextSecondarySeed method is used in the same contexts as
	 * MyThreadLocalRandom, but only for transient usages such as random
	 * adaptive spin/block sequences for which a cheap RNG suffices and for
	 * which it could in principle disrupt user-visible statistical properties
	 * of the main MyThreadLocalRandom if we were to use it.
	 *
	 * Note: Because of package-protection issues, versions of some these
	 * methods also appear in some subpackage classes.
	 */

	/**
	 * Returns a pseudorandom {@code long} value between zero (inclusive) and
	 * the specified bound (exclusive).
	 *
	 * @param bound
	 *            the upper bound (exclusive). Must be positive.
	 * @return a pseudorandom {@code long} value between zero (inclusive) and
	 *         the bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code bound} is not positive
	 */
	public long nextLong(final long bound)
	{
		if (bound <= 0)
		{
			throw new IllegalArgumentException(BadBound);
		}
		long r = mix64(nextSeed());
		final long m = bound - 1;
		if ((bound & m) == 0L)
		{
			r &= m;
		}
		else
		{ // reject over-represented candidates
			for (long u = r >>> 1; u + m - (r = u % bound) < 0L; u = mix64(nextSeed()) >>> 1)
			{
				;
			}
		}
		return r;
	}

	/**
	 * Returns a pseudorandom {@code long} value between the specified origin
	 * (inclusive) and the specified bound (exclusive).
	 *
	 * @param origin
	 *            the least value returned
	 * @param bound
	 *            the upper bound (exclusive)
	 * @return a pseudorandom {@code long} value between the origin (inclusive)
	 *         and the bound (exclusive)
	 * @throws IllegalArgumentException
	 *             if {@code origin} is greater than or equal to {@code bound}
	 */
	public long nextLong(final long origin, final long bound)
	{
		if (origin >= bound)
		{
			throw new IllegalArgumentException(BadRange);
		}
		return internalNextLong(origin, bound);
	}

	/**
	 * Throws {@code UnsupportedOperationException}. Setting seeds in this
	 * generator is not supported.
	 *
	 * @throws UnsupportedOperationException
	 *             always
	 */
	@Override
	public void setSeed(final long seed)
	{
		// only allow call from super() constructor
		if (initialized)
		{
			throw new UnsupportedOperationException();
		}
	}

	// Serialization support

	/**
	 * Returns the {@link #current() current} thread's
	 * {@code MyThreadLocalRandom}.
	 *
	 * @return the {@link #current() current} thread's
	 *         {@code MyThreadLocalRandom}
	 */
	private Object readResolve()
	{
		return current();
	}

	/**
	 * Saves the {@code MyThreadLocalRandom} to a stream (that is, serializes
	 * it).
	 *
	 * @param s
	 *            the stream
	 * @throws java.io.IOException
	 *             if an I/O error occurs
	 */
	private void writeObject(final java.io.ObjectOutputStream s) throws java.io.IOException
	{

		final java.io.ObjectOutputStream.PutField fields = s.putFields();
		fields.put("rnd", UNSAFE.getLong(Thread.currentThread(), SEED));
		fields.put("initialized", true);
		s.writeFields();
	}

	// We must define this, but never use it.
	@Override
	protected int next(final int bits)
	{
		return (int)(mix64(nextSeed()) >>> (64 - bits));
	}

	/**
	 * The form of nextDouble used by DoubleStream Spliterators.
	 *
	 * @param origin
	 *            the least value, unless greater than bound
	 * @param bound
	 *            the upper bound (exclusive), must not equal origin
	 * @return a pseudorandom value
	 */
	final double internalNextDouble(final double origin, final double bound)
	{
		double r = (nextLong() >>> 11) * DOUBLE_UNIT;
		if (origin < bound)
		{
			r = r * (bound - origin) + origin;
			if (r >= bound)
			{
				r = Double.longBitsToDouble(Double.doubleToLongBits(bound) - 1);
			}
		}
		return r;
	}

	/**
	 * The form of nextInt used by IntStream Spliterators. Exactly the same as
	 * long version, except for types.
	 *
	 * @param origin
	 *            the least value, unless greater than bound
	 * @param bound
	 *            the upper bound (exclusive), must not equal origin
	 * @return a pseudorandom value
	 */
	final int internalNextInt(final int origin, final int bound)
	{
		int r = mix32(nextSeed());
		if (origin < bound)
		{
			final int n = bound - origin, m = n - 1;
			if ((n & m) == 0)
			{
				r = (r & m) + origin;
			}
			else if (n > 0)
			{
				for (int u = r >>> 1; u + m - (r = u % n) < 0; u = mix32(nextSeed()) >>> 1)
				{
					;
				}
				r += origin;
			}
			else
			{
				while (r < origin || r >= bound)
				{
					r = mix32(nextSeed());
				}
			}
		}
		return r;
	}

	/**
	 * The form of nextLong used by LongStream Spliterators. If origin is
	 * greater than bound, acts as unbounded form of nextLong, else as bounded
	 * form.
	 *
	 * @param origin
	 *            the least value, unless greater than bound
	 * @param bound
	 *            the upper bound (exclusive), must not equal origin
	 * @return a pseudorandom value
	 */
	final long internalNextLong(final long origin, final long bound)
	{
		long r = mix64(nextSeed());
		if (origin < bound)
		{
			final long n = bound - origin, m = n - 1;
			if ((n & m) == 0L)
			{
				r = (r & m) + origin;
			}
			else if (n > 0L)
			{ // reject over-represented candidates
				for (long u = r >>> 1; // ensure nonnegative
				u + m - (r = u % n) < 0L; // rejection check
				u = mix64(nextSeed()) >>> 1)
				{
					;
				}
				r += origin;
			}
			else
			{ // range not representable as long
				while (r < origin || r >= bound)
				{
					r = mix64(nextSeed());
				}
			}
		}
		return r;
	}

	final long nextSeed()
	{
		Thread t;
		long r; // read and update per-thread seed
		UNSAFE.putLong(t = Thread.currentThread(), SEED, r = UNSAFE.getLong(t, SEED) + GAMMA);
		return r;
	}
	/**
	 * Spliterator for double streams.
	 */
	static final class RandomDoublesSpliterator implements Spliterator.OfDouble
	{
		long index;
		final long fence;
		final double origin;
		final double bound;

		RandomDoublesSpliterator(final long index, final long fence, final double origin, final double bound)
		{
			this.index = index;
			this.fence = fence;
			this.origin = origin;
			this.bound = bound;
		}

		@Override
		public int characteristics()
		{
			return (Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL | Spliterator.IMMUTABLE);
		}

		@Override
		public long estimateSize()
		{
			return fence - index;
		}

		@Override
		public void forEachRemaining(final DoubleConsumer consumer)
		{
			if (consumer == null)
			{
				throw new NullPointerException();
			}
			long i = index;
			final long f = fence;
			if (i < f)
			{
				index = f;
				final double o = origin, b = bound;
				final MyThreadLocalRandom rng = MyThreadLocalRandom.current();
				do
				{
					consumer.accept(rng.internalNextDouble(o, b));
				} while (++i < f);
			}
		}

		@Override
		public boolean tryAdvance(final DoubleConsumer consumer)
		{
			if (consumer == null)
			{
				throw new NullPointerException();
			}
			final long i = index, f = fence;
			if (i < f)
			{
				consumer.accept(MyThreadLocalRandom.current().internalNextDouble(origin, bound));
				index = i + 1;
				return true;
			}
			return false;
		}

		@Override
		public RandomDoublesSpliterator trySplit()
		{
			final long i = index, m = (i + fence) >>> 1;
			return (m <= i) ? null : new RandomDoublesSpliterator(i, index = m, origin, bound);
		}
	}
	/**
	 * Spliterator for int streams. We multiplex the four int versions into one
	 * class by treating a bound less than origin as unbounded, and also by
	 * treating "infinite" as equivalent to Long.MAX_VALUE. For splits, it uses
	 * the standard divide-by-two approach. The long and double versions of this
	 * class are identical except for types.
	 */
	static final class RandomIntsSpliterator implements Spliterator.OfInt
	{
		long index;
		final long fence;
		final int origin;
		final int bound;

		RandomIntsSpliterator(final long index, final long fence, final int origin, final int bound)
		{
			this.index = index;
			this.fence = fence;
			this.origin = origin;
			this.bound = bound;
		}

		@Override
		public int characteristics()
		{
			return (Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL | Spliterator.IMMUTABLE);
		}

		@Override
		public long estimateSize()
		{
			return fence - index;
		}

		@Override
		public void forEachRemaining(final IntConsumer consumer)
		{
			if (consumer == null)
			{
				throw new NullPointerException();
			}
			long i = index;
			final long f = fence;
			if (i < f)
			{
				index = f;
				final int o = origin, b = bound;
				final MyThreadLocalRandom rng = MyThreadLocalRandom.current();
				do
				{
					consumer.accept(rng.internalNextInt(o, b));
				} while (++i < f);
			}
		}

		@Override
		public boolean tryAdvance(final IntConsumer consumer)
		{
			if (consumer == null)
			{
				throw new NullPointerException();
			}
			final long i = index, f = fence;
			if (i < f)
			{
				consumer.accept(MyThreadLocalRandom.current().internalNextInt(origin, bound));
				index = i + 1;
				return true;
			}
			return false;
		}

		@Override
		public RandomIntsSpliterator trySplit()
		{
			final long i = index, m = (i + fence) >>> 1;
			return (m <= i) ? null : new RandomIntsSpliterator(i, index = m, origin, bound);
		}
	}

	/**
	 * Spliterator for long streams.
	 */
	static final class RandomLongsSpliterator implements Spliterator.OfLong
	{
		long index;
		final long fence;
		final long origin;
		final long bound;

		RandomLongsSpliterator(final long index, final long fence, final long origin, final long bound)
		{
			this.index = index;
			this.fence = fence;
			this.origin = origin;
			this.bound = bound;
		}

		@Override
		public int characteristics()
		{
			return (Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL | Spliterator.IMMUTABLE);
		}

		@Override
		public long estimateSize()
		{
			return fence - index;
		}

		@Override
		public void forEachRemaining(final LongConsumer consumer)
		{
			if (consumer == null)
			{
				throw new NullPointerException();
			}
			long i = index;
			final long f = fence;
			if (i < f)
			{
				index = f;
				final long o = origin, b = bound;
				final MyThreadLocalRandom rng = MyThreadLocalRandom.current();
				do
				{
					consumer.accept(rng.internalNextLong(o, b));
				} while (++i < f);
			}
		}

		@Override
		public boolean tryAdvance(final LongConsumer consumer)
		{
			if (consumer == null)
			{
				throw new NullPointerException();
			}
			final long i = index, f = fence;
			if (i < f)
			{
				consumer.accept(MyThreadLocalRandom.current().internalNextLong(origin, bound));
				index = i + 1;
				return true;
			}
			return false;
		}

		@Override
		public RandomLongsSpliterator trySplit()
		{
			final long i = index, m = (i + fence) >>> 1;
			return (m <= i) ? null : new RandomLongsSpliterator(i, index = m, origin, bound);
		}

	}
}
