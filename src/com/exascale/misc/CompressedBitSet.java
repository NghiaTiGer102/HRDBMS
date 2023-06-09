package com.exascale.misc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

public final class CompressedBitSet extends BitSet implements Externalizable, Iterable<Integer>, BitmapStorage, LogicalElement<CompressedBitSet>
{
	/**
	 * whether we adjust after some aggregation by adding in zeroes *
	 */
	public static final boolean ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING = true;

	/**
	 * The Constant WORD_IN_BITS represents the number of bits in a long.
	 */
	public static final int WORD_IN_BITS = 64;

	static final long serialVersionUID = 1L;

	/**
	 * The buffer
	 */
	final Buffer buffer;

	/**
	 * The current (last) running length word.
	 */
	private RunningLengthWord rlw = null;

	/**
	 * sizeInBits: number of bits in the (uncompressed) bitmap.
	 */
	private int sizeInBits = 0;

	/**
	 * Creates an empty bitmap (no bit set to true).
	 */
	public CompressedBitSet()
	{
		this(new LongArray());
	}

	/**
	 * Creates a bitmap with the specified ByteBuffer backend. It assumes that a
	 * bitmap was serialized at this location. It is effectively "deserialized"
	 * though the actual content is not copied. This might be useful for
	 * implementing memory-mapped bitmaps.
	 *
	 * @param buffer
	 *            data source
	 */
	public CompressedBitSet(final ByteBuffer buffer)
	{
		final IntBuffer ib = buffer.asIntBuffer();
		this.sizeInBits = ib.get(0);
		final int sizeInWords = ib.get(1);
		final int rlwposition = ib.get(2 + sizeInWords * 2);
		final LongBuffer lb = buffer.asLongBuffer();
		lb.position(1);
		this.buffer = new LongBufferWrapper(lb.slice(), sizeInWords);
		this.rlw = new RunningLengthWord(this.buffer, rlwposition);
	}

	/**
	 * Sets explicitly the buffer size (in 64-bit words). The initial memory
	 * usage will be "bufferSize * 64". For large poorly compressible bitmaps,
	 * using large values may improve performance.
	 * 
	 * If the requested bufferSize is less than 1, a value of 1 is used by
	 * default. In particular, negative values of bufferSize are effectively
	 * ignored.
	 *
	 * @param bufferSize
	 *            number of 64-bit words reserved when the object is created)
	 */
	public CompressedBitSet(final int bufferSize)
	{
		this(new LongArray(bufferSize));
	}

	/**
	 * Creates a bitmap with the specified java.nio.LongBuffer backend. The
	 * content of the LongBuffer is discarded.
	 * 
	 * @param buffer
	 *            data source
	 */
	public CompressedBitSet(final LongBuffer buffer)
	{
		this(new LongBufferWrapper(buffer));
	}

	private CompressedBitSet(final Buffer buffer)
	{
		this.buffer = buffer;
		this.rlw = new RunningLengthWord(this.buffer, 0);
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise AND values of the
	 * provided bitmaps.
	 * 
	 * It may or may not be faster than doing the aggregation two-by-two
	 * (A.and(B).and(C)).
	 * 
	 * If only one bitmap is provided, it is returned as is.
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 *
	 * @param bitmaps
	 *            bitmaps to AND together
	 * @return result of the AND
	 * @since 0.4.3
	 */
	public static CompressedBitSet and(final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length == 1)
		{
			return bitmaps[0];
		}
		if (bitmaps.length == 2)
		{
			return bitmaps[0].and(bitmaps[1]);
		}

		final int initialSize = calculateInitialSize(bitmaps);
		final CompressedBitSet answer = new CompressedBitSet(initialSize);
		final CompressedBitSet tmp = new CompressedBitSet(initialSize);
		bitmaps[0].andToContainer(bitmaps[1], answer);
		for (int k = 2; k < bitmaps.length; ++k)
		{
			answer.andToContainer(bitmaps[k], tmp);
			tmp.swap(answer);
			tmp.clear();
		}
		return answer;
	}

	/**
	 * Returns the cardinality of the result of a bitwise AND of the values of
	 * the provided bitmaps. Avoids allocating an intermediate bitmap to hold
	 * the result of the AND.
	 *
	 * @param bitmaps
	 *            bitmaps to AND
	 * @return the cardinality
	 * @since 0.4.3
	 */
	public static int andCardinality(final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length == 1)
		{
			return bitmaps[0].cardinality();
		}
		final BitCounter counter = new BitCounter();
		andWithContainer(counter, bitmaps);
		return counter.getCount();
	}

	/**
	 * For internal use. Computes the bitwise and of the provided bitmaps and
	 * stores the result in the container.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param container
	 *            where the result is stored
	 * @param bitmaps
	 *            bitmaps to AND
	 * @since 0.4.3
	 */
	public static void andWithContainer(final BitmapStorage container, final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length == 1)
		{
			throw new IllegalArgumentException("Need at least one bitmap");
		}
		if (bitmaps.length == 2)
		{
			bitmaps[0].andToContainer(bitmaps[1], container);
			return;
		}

		final int initialSize = calculateInitialSize(bitmaps);
		CompressedBitSet answer = new CompressedBitSet(initialSize);
		CompressedBitSet tmp = new CompressedBitSet(initialSize);

		bitmaps[0].andToContainer(bitmaps[1], answer);
		for (int k = 2; k < bitmaps.length - 1; ++k)
		{
			answer.andToContainer(bitmaps[k], tmp);
			final CompressedBitSet tmp2 = answer;
			answer = tmp;
			tmp = tmp2;
			tmp.clear();
		}
		answer.andToContainer(bitmaps[bitmaps.length - 1], container);
	}

	/**
	 * Return a bitmap with the bit set to true at the given positions. The
	 * positions should be given in sorted order.
	 * 
	 * (This is a convenience method.)
	 *
	 * @param setBits
	 *            list of set bit positions
	 * @return the bitmap
	 * @since 0.4.5
	 */
	public static CompressedBitSet bitmapOf(final int... setBits)
	{
		final CompressedBitSet a = new CompressedBitSet();
		for (final int k : setBits)
		{
			a.set(k);
		}
		return a;
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise OR values of the
	 * provided bitmaps. This is typically faster than doing the aggregation
	 * two-by-two (A.or(B).or(C).or(D)).
	 * 
	 * If only one bitmap is provided, it is returned as is.
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 *
	 * @param bitmaps
	 *            bitmaps to OR together
	 * @return result of the OR
	 * @since 0.4.0
	 */
	public static CompressedBitSet or(final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length == 1)
		{
			return bitmaps[0];
		}

		final int largestSize = calculateInitialSize(bitmaps);
		final CompressedBitSet container = new CompressedBitSet((int)(largestSize * 1.5));
		orWithContainer(container, bitmaps);
		return container;
	}

	/**
	 * Returns the cardinality of the result of a bitwise OR of the values of
	 * the provided bitmaps. Avoids allocating an intermediate bitmap to hold
	 * the result of the OR.
	 *
	 * @param bitmaps
	 *            bitmaps to OR
	 * @return the cardinality
	 * @since 0.4.0
	 */
	public static int orCardinality(final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length == 1)
		{
			return bitmaps[0].cardinality();
		}
		final BitCounter counter = new BitCounter();
		orWithContainer(counter, bitmaps);
		return counter.getCount();
	}

	/**
	 * Uses an adaptive technique to compute the logical OR. Mostly for internal
	 * use.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param container
	 *            where the aggregate is written.
	 * @param bitmaps
	 *            to be aggregated
	 */
	public static void orWithContainer(final BitmapStorage container, final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length < 2)
		{
			throw new IllegalArgumentException("You should provide at least two bitmaps, provided " + bitmaps.length);
		}
		FastAggregation.orToContainer(container, bitmaps);
	}

	/**
	 * Compute a Boolean threshold function: bits are true where at least t
	 * bitmaps have a true bit.
	 *
	 * @param t
	 *            the threshold
	 * @param bitmaps
	 *            input data
	 * @return the aggregated bitmap
	 * @since 0.8.1
	 */
	public static CompressedBitSet threshold(final int t, final CompressedBitSet... bitmaps)
	{
		final CompressedBitSet container = new CompressedBitSet();
		thresholdWithContainer(container, t, bitmaps);
		return container;
	}

	/**
	 * Compute a Boolean threshold function: bits are true where at least T
	 * bitmaps have a true bit.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param t
	 *            the threshold
	 * @param bitmaps
	 *            input data
	 * @param container
	 *            where we write the aggregated bitmap
	 * @since 0.8.1
	 */
	public static void thresholdWithContainer(final BitmapStorage container, final int t, final CompressedBitSet... bitmaps)
	{
		(new RunningBitmapMerge()).symmetric(new ThresholdFuncBitmap(t), container, bitmaps);
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise XOR values of the
	 * provided bitmaps. This is typically faster than doing the aggregation
	 * two-by-two (A.xor(B).xor(C).xor(D)).
	 * 
	 * If only one bitmap is provided, it is returned as is.
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 *
	 * @param bitmaps
	 *            bitmaps to XOR together
	 * @return result of the XOR
	 */
	public static CompressedBitSet xor(final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length == 1)
		{
			return bitmaps[0];
		}

		final int largestSize = calculateInitialSize(bitmaps);

		final int size = (int)(largestSize * 1.5);
		final CompressedBitSet container = new CompressedBitSet(size);
		xorWithContainer(container, bitmaps);
		return container;
	}

	/**
	 * Uses an adaptive technique to compute the logical XOR. Mostly for
	 * internal use.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param container
	 *            where the aggregate is written.
	 * @param bitmaps
	 *            to be aggregated
	 */
	public static void xorWithContainer(final BitmapStorage container, final CompressedBitSet... bitmaps)
	{
		if (bitmaps.length < 2)
		{
			throw new IllegalArgumentException("You should provide at least two bitmaps, provided " + bitmaps.length);
		}
		FastAggregation.xorToContainer(container, bitmaps);
	}

	/*
	 * @see java.lang.Object#clone()
	 */

	private static int calculateInitialSize(final CompressedBitSet... bitmaps)
	{
		int initialSize = 0;
		for (final CompressedBitSet bitmap : bitmaps)
		{
			initialSize = Math.max(bitmap.buffer.sizeInWords(), initialSize);
		}
		return initialSize;
	}

	private static boolean mergeLiteralWordInCurrentRunningLength(final boolean value, final boolean rb, final long rl, final int wordPosition)
	{
		return (value == rb || rl == 0) && wordPosition == 1;
	}

	static int maxSizeInBits(final CompressedBitSet... bitmaps)
	{
		int maxSizeInBits = 0;
		for (final CompressedBitSet bitmap : bitmaps)
		{
			maxSizeInBits = Math.max(maxSizeInBits, bitmap.sizeInBits());
		}
		return maxSizeInBits;
	}

	/**
	 * Adding literal word directly to the bitmap (for expert use). Since this
	 * modifies the bitmap, this method is not thread-safe.
	 *
	 * @param newData
	 *            the word
	 */
	@Override
	public void addLiteralWord(final long newData)
	{
		this.sizeInBits += WORD_IN_BITS;
		insertLiteralWord(newData);
	}

	/**
	 * For experts: You want to add many zeroes or ones? This is the method you
	 * use.
	 * 
	 * Since this modifies the bitmap, this method is not thread-safe.
	 *
	 * @param v
	 *            the boolean value
	 * @param number
	 *            the number
	 */

	@Override
	public void addStreamOfEmptyWords(final boolean v, final long number)
	{
		if (number == 0)
		{
			return;
		}
		this.sizeInBits += number * WORD_IN_BITS;
		fastaddStreamOfEmptyWords(v, number);
	}

	/**
	 * if you have several literal words to copy over, this might be faster.
	 *
	 * Since this modifies the bitmap, this method is not thread-safe.
	 *
	 * @param buffer
	 *            the buffer wrapping the literal words
	 * @param start
	 *            the starting point in the array
	 * @param number
	 *            the number of literal words to add
	 */

	@Override
	public void addStreamOfLiteralWords(final Buffer buffer, final int start, final int number)
	{
		int leftOverNumber = number;
		while (leftOverNumber > 0)
		{
			final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
			final int whatWeCanAdd = leftOverNumber < RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords ? leftOverNumber : RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords;
			this.rlw.setNumberOfLiteralWords(numberOfLiteralWords + whatWeCanAdd);
			leftOverNumber -= whatWeCanAdd;
			this.buffer.push_back(buffer, start, whatWeCanAdd);
			this.sizeInBits += whatWeCanAdd * WORD_IN_BITS;
			if (leftOverNumber > 0)
			{
				this.buffer.push_back(0);
				this.rlw.position = this.buffer.sizeInWords() - 1;
			}
		}
	}

	/**
	 * Same as addStreamOfLiteralWords, but the words are negated.
	 *
	 * Since this modifies the bitmap, this method is not thread-safe.
	 *
	 * @param buffer
	 *            the buffer wrapping the literal words
	 * @param start
	 *            the starting point in the array
	 * @param number
	 *            the number of literal words to add
	 */

	@Override
	public void addStreamOfNegatedLiteralWords(final Buffer buffer, final int start, final int number)
	{
		int leftOverNumber = number;
		while (leftOverNumber > 0)
		{
			final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
			final int whatWeCanAdd = leftOverNumber < RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords ? leftOverNumber : RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords;
			this.rlw.setNumberOfLiteralWords(numberOfLiteralWords + whatWeCanAdd);
			leftOverNumber -= whatWeCanAdd;
			this.buffer.negative_push_back(buffer, start, whatWeCanAdd);
			this.sizeInBits += whatWeCanAdd * WORD_IN_BITS;
			if (leftOverNumber > 0)
			{
				this.buffer.push_back(0);
				this.rlw.position = this.buffer.sizeInWords() - 1;
			}
		}
	}

	/**
	 * Adding words directly to the bitmap (for expert use).
	 * 
	 * This method adds bits in words of 4*8 bits. It is not to be confused with
	 * the set method which sets individual bits.
	 * 
	 * Most users will want the set method.
	 * 
	 * Example: if you add word 321 to an empty bitmap, you are have added (in
	 * binary notation) 0b101000001, so you have effectively called set(0),
	 * set(6), set(8) in sequence.
	 * 
	 * Since this modifies the bitmap, this method is not thread-safe.
	 * 
	 * API change: prior to version 0.8.3, this method was called add.
	 *
	 * @param newData
	 *            the word
	 */

	@Override
	public void addWord(final long newData)
	{
		addWord(newData, WORD_IN_BITS);
	}

	/**
	 * Adding words directly to the bitmap (for expert use). Since this modifies
	 * the bitmap, this method is not thread-safe.
	 * 
	 * API change: prior to version 0.8.3, this method was called add.
	 *
	 * @param newData
	 *            the word
	 * @param bitsThatMatter
	 *            the number of significant bits (by default it should be 64)
	 */
	public void addWord(final long newData, final int bitsThatMatter)
	{
		this.sizeInBits += bitsThatMatter;
		if (newData == 0)
		{
			insertEmptyWord(false);
		}
		else if (newData == ~0l)
		{
			insertEmptyWord(true);
		}
		else
		{
			insertLiteralWord(newData);
		}
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise AND values of the
	 * current bitmap with some other bitmap.
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the EWAH compressed bitmap
	 * @since 0.4.3
	 */

	@Override
	public CompressedBitSet and(final CompressedBitSet a)
	{
		final int size = this.buffer.sizeInWords() > a.buffer.sizeInWords() ? this.buffer.sizeInWords() : a.buffer.sizeInWords();
		final CompressedBitSet container = new CompressedBitSet(size);
		andToContainer(a, container);
		return container;
	}

	/**
	 * Returns the cardinality of the result of a bitwise AND of the values of
	 * the current bitmap with some other bitmap. Avoids allocating an
	 * intermediate bitmap to hold the result of the OR.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the cardinality
	 * @since 0.4.0
	 */
	public int andCardinality(final CompressedBitSet a)
	{
		final BitCounter counter = new BitCounter();
		andToContainer(a, counter);
		return counter.getCount();
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise AND NOT values of
	 * the current bitmap with some other bitmap.
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the EWAH compressed bitmap
	 */

	@Override
	public CompressedBitSet andNot(final CompressedBitSet a)
	{
		final int size = this.buffer.sizeInWords() > a.buffer.sizeInWords() ? this.buffer.sizeInWords() : a.buffer.sizeInWords();
		final CompressedBitSet container = new CompressedBitSet(size);
		andNotToContainer(a, container);
		return container;
	}

	/**
	 * Returns the cardinality of the result of a bitwise AND NOT of the values
	 * of the current bitmap with some other bitmap. Avoids allocating an
	 * intermediate bitmap to hold the result of the OR.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the cardinality
	 * @since 0.4.0
	 */
	public int andNotCardinality(final CompressedBitSet a)
	{
		final BitCounter counter = new BitCounter();
		andNotToContainer(a, counter);
		return counter.getCount();
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise AND NOT values of
	 * the current bitmap with some other bitmap. This method is expected to be
	 * faster than doing A.and(B.clone().not()).
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * The current bitmap is not modified.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @param container
	 *            where to store the result
	 * @since 0.4.0
	 */
	public void andNotToContainer(final CompressedBitSet a, final BitmapStorage container)
	{
		container.clear();
		final EWAHIterator i = getEWAHIterator();
		final EWAHIterator j = a.getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while ((rlwi.size() > 0) && (rlwj.size() > 0))
		{
			while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0))
			{
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				if (((predator.getRunningBit()) && (i_is_prey)) || ((!predator.getRunningBit()) && (!i_is_prey)))
				{
					container.addStreamOfEmptyWords(false, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				}
				else if (i_is_prey)
				{
					final long index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
				}
				else
				{
					final long index = prey.dischargeNegated(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(true, predator.getRunningLength() - index);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if (nbre_literal > 0)
			{
				for (int k = 0; k < nbre_literal; ++k)
				{
					container.addWord(rlwi.getLiteralWordAt(k) & (~rlwj.getLiteralWordAt(k)));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
		if (i_remains)
		{
			remaining.discharge(container);
		}
		if (ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING)
		{
			container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
		}
	}

	/**
	 * Computes new compressed bitmap containing the bitwise AND values of the
	 * current bitmap with some other bitmap.
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * The current bitmap is not modified.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @param container
	 *            where we store the result
	 * @since 0.4.0
	 */
	public void andToContainer(final CompressedBitSet a, final BitmapStorage container)
	{
		container.clear();
		final EWAHIterator i = a.getEWAHIterator();
		final EWAHIterator j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while ((rlwi.size() > 0) && (rlwj.size() > 0))
		{
			while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0))
			{
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				if (!predator.getRunningBit())
				{
					container.addStreamOfEmptyWords(false, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				}
				else
				{
					final long index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if (nbre_literal > 0)
			{
				for (int k = 0; k < nbre_literal; ++k)
				{
					container.addWord(rlwi.getLiteralWordAt(k) & rlwj.getLiteralWordAt(k));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}

		if (ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING)
		{
			container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
		}
	}

	/**
	 * reports the number of bits set to true. Running time is proportional to
	 * compressed size (as reported by sizeInBytes).
	 *
	 * @return the number of bits set to true
	 */
	@Override
	public int cardinality()
	{
		int counter = 0;
		final EWAHIterator i = this.getEWAHIterator();
		while (i.hasNext())
		{
			final RunningLengthWord localrlw = i.next();
			if (localrlw.getRunningBit())
			{
				counter += WORD_IN_BITS * localrlw.getRunningLength();
			}
			final int numberOfLiteralWords = localrlw.getNumberOfLiteralWords();
			final int literalWords = i.literalWords();
			for (int j = 0; j < numberOfLiteralWords; ++j)
			{
				counter += Long.bitCount(i.buffer().getWord(literalWords + j));
			}
		}
		return counter;
	}

	/**
	 * Iterator over the chunk of bits.
	 *
	 * The current bitmap is not modified.
	 *
	 * @return the chunk iterator
	 */
	public ChunkIterator chunkIterator()
	{
		return new ChunkIteratorImpl(this.getEWAHIterator(), this.sizeInBits);
	}

	/**
	 * Clear any set bits and set size in bits back to 0
	 */

	@Override
	public void clear()
	{
		this.sizeInBits = 0;
		this.buffer.clear();
		this.rlw.position = 0;
	}

	/**
	 * Set the bit at position i to false.
	 * 
	 * Though you can clear the bits in any order (e.g., clear(100), clear(10),
	 * clear(1), you will typically get better performance if you clear the bits
	 * in increasing order (e.g., clear(1), clear(10), clear(100)).
	 * 
	 * Clearing a bit that is larger than the biggest bit is a constant time
	 * operation. Clearing a bit that is smaller than the biggest bit can
	 * require time proportional to the compressed size of the bitmap, as the
	 * bitmap may need to be rewritten.
	 * 
	 * Since this modifies the bitmap, this method is not thread-safe.
	 *
	 * @param i
	 *            the index
	 * @return true if the value was unset
	 * @throws IndexOutOfBoundsException
	 *             if i is negative or greater than Integer.MAX_VALUE - 64
	 */
	@Override
	public void clear(final int i)
	{
		set(i, false);
	}

	/**
	 * Iterator over the clear bits. The location of the clear bits is returned,
	 * in increasing order.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the int iterator
	 */
	public IntIterator clearIntIterator()
	{
		return new ClearIntIterator(this.getEWAHIterator(), this.sizeInBits);
	}

	@Override
	public CompressedBitSet clone()
	{
		final CompressedBitSet clone = new CompressedBitSet(this.buffer.clone());
		clone.sizeInBits = this.sizeInBits;
		clone.rlw = new RunningLengthWord(clone.buffer, this.rlw.position);
		return clone;
	}

	/*
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */

	/**
	 * Returns a new compressed bitmap containing the composition of the current
	 * bitmap with some other bitmap.
	 *
	 * The composition A.compose(B) is defined as follows: we retain the ith set
	 * bit of A only if the ith bit of B is set. For example, if you have the
	 * following bitmap A = { 0, 1, 0, 1, 1, 0 } and want to keep only the
	 * second and third ones, you can call A.compose(B) with B = { 0, 1, 1 } and
	 * you will get C = { 0, 0, 0, 1, 1, 0 }.
	 *
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 *
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the EWAH compressed bitmap
	 */

	@Override
	public CompressedBitSet compose(final CompressedBitSet a)
	{
		final int size = this.buffer.sizeInWords();
		final CompressedBitSet container = new CompressedBitSet(size);
		composeToContainer(a, container);
		return container;
	}

	/*
	 * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
	 */

	/**
	 * Computes a new compressed bitmap containing the composition of the
	 * current bitmap with some other bitmap.
	 * 
	 * The composition A.compose(B) is defined as follows: we retain the ith set
	 * bit of A only if the ith bit of B is set. For example, if you have the
	 * following bitmap A = { 0, 1, 0, 1, 1, 0 } and want to keep only the
	 * second and third ones, you can call A.compose(B) with B = { 0, 1, 1 } and
	 * you will get C = { 0, 0, 0, 1, 1, 0 }.
	 *
	 * The current bitmap is not modified.
	 *
	 * The content of the container is overwritten.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @param container
	 *            where we store the result
	 */
	public void composeToContainer(final CompressedBitSet a, final CompressedBitSet container)
	{
		container.clear();
		final ChunkIterator iterator = chunkIterator();
		final ChunkIterator aIterator = a.chunkIterator();
		int index = 0;
		while (iterator.hasNext() && aIterator.hasNext())
		{
			if (!iterator.nextBit())
			{
				final int length = iterator.nextLength();
				index += length;
				container.setSizeInBits(index, false);
				iterator.move(length);
			}
			else
			{
				final int length = Math.min(iterator.nextLength(), aIterator.nextLength());
				index += length;
				container.setSizeInBits(index, aIterator.nextBit());
				iterator.move(length);
				aIterator.move(length);
			}
		}
		container.setSizeInBits(sizeInBits, false);
	}

	/**
	 * Deserialize.
	 *
	 * @param in
	 *            the DataInput stream
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void deserialize(final DataInput in) throws IOException
	{
		this.sizeInBits = in.readInt();
		final int sizeInWords = in.readInt();
		this.buffer.clear();// This creates a buffer with 1 word in it already!
		this.buffer.removeLastWord();
		this.buffer.ensureCapacity(sizeInWords);
		for (int i = 0; i < sizeInWords; ++i)
		{
			this.buffer.push_back(in.readLong());
		}
		this.rlw = new RunningLengthWord(this.buffer, in.readInt());
	}

	/**
	 * Check to see whether the two compressed bitmaps contain the same set
	 * bits.
	 *
	 * @see java.lang.Object#equals(java.lang.Object)
	 */

	@Override
	public boolean equals(final Object o)
	{
		if (o instanceof CompressedBitSet)
		{
			try
			{
				this.xorToContainer((CompressedBitSet)o, new NonEmptyVirtualStorage());
				return true;
			}
			catch (final NonEmptyVirtualStorage.NonEmptyException e)
			{
				return false;
			}
		}
		return false;
	}

	/**
	 * Query the value of a single bit. Relying on this method when speed is
	 * needed is discouraged. The complexity is linear with the size of the
	 * bitmap.
	 * 
	 * (This implementation is based on zhenjl's Go version of JavaEWAH.)
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param i
	 *            the bit we are interested in
	 * @return whether the bit is set to true
	 */
	@Override
	public boolean get(final int i)
	{
		if ((i < 0) || (i >= this.sizeInBits))
		{
			return false;
		}
		int wordChecked = 0;
		final IteratingRLW j = getIteratingRLW();
		final int wordi = i / WORD_IN_BITS;
		while (wordChecked <= wordi)
		{
			wordChecked += j.getRunningLength();
			if (wordi < wordChecked)
			{
				return j.getRunningBit();
			}
			if (wordi < wordChecked + j.getNumberOfLiteralWords())
			{
				final long w = j.getLiteralWordAt(wordi - wordChecked);
				return (w & (1l << i)) != 0;
			}
			wordChecked += j.getNumberOfLiteralWords();
			j.next();
		}
		return false;
	}

	/**
	 * Gets an EWAHIterator over the data. This is a customized iterator which
	 * iterates over run length words. For experts only.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the EWAHIterator
	 */
	public EWAHIterator getEWAHIterator()
	{
		return new EWAHIterator(this.buffer);
	}

	/**
	 * getFirstSetBit is a light-weight method that returns the location of the
	 * set bit (=1) or -1 if there is none.
	 * 
	 * @return location of the first set bit or -1
	 */
	public int getFirstSetBit()
	{
		int nword = 0;
		final int siw = this.buffer.sizeInWords();
		for (int pos = 0; pos < siw; ++pos)
		{
			final long rl = RunningLengthWord.getRunningLength(this.buffer, pos);
			final boolean rb = RunningLengthWord.getRunningBit(this.buffer, pos);
			if ((rl > 0) && rb)
			{
				return nword * WORD_IN_BITS;
			}
			nword += rl;
			final long lw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, pos);
			if (lw > 0)
			{
				final long word = this.buffer.getWord(pos + 1);
				if (word != 0l)
				{
					final long T = word & -word;
					return nword * WORD_IN_BITS + Long.bitCount(T - 1);
				}
			}
		}
		return -1;
	}

	/**
	 * Gets an IteratingRLW to iterate over the data. For experts only.
	 * 
	 * Note that iterator does not know about the size in bits of the bitmap:
	 * the size in bits is effectively rounded up to the nearest multiple of 64.
	 * However, if you materialize a bitmap from an iterator, you can set the
	 * desired size in bits using the setSizeInBitsWithinLastWord methods:
	 * 
	 * <code>
	 *  CompressedBitSet n = IteratorUtil.materialize(bitmap.getIteratingRLW()));
	 *  n.setSizeInBitsWithinLastWord(bitmap.sizeInBits());
	 *  </code>
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the IteratingRLW iterator corresponding to this bitmap
	 */
	public IteratingRLW getIteratingRLW()
	{
		return new IteratingBufferedRunningLengthWord(this);
	}

	/**
	 * Returns a customized hash code (based on Karp-Rabin). Naturally, if the
	 * bitmaps are equal, they will hash to the same value.
	 * 
	 * The current bitmap is not modified.
	 */

	@Override
	public int hashCode()
	{
		int karprabin = 0;
		final int B = 0x9e3779b1;
		final EWAHIterator i = this.getEWAHIterator();
		while (i.hasNext())
		{
			i.next();
			if (i.rlw.getRunningBit())
			{
				final long rl = i.rlw.getRunningLength();
				karprabin += B * (rl & 0xFFFFFFFF);
				karprabin += B * ((rl >>> 32) & 0xFFFFFFFF);
			}
			final int nlw = i.rlw.getNumberOfLiteralWords();
			final int lw = i.literalWords();
			for (int k = 0; k < nlw; ++k)
			{
				final long W = this.buffer.getWord(lw + k);
				karprabin += B * (W & 0xFFFFFFFF);
				karprabin += B * ((W >>> 32) & 0xFFFFFFFF);
			}
		}
		return karprabin;
	}

	/**
	 * Return true if the two CompressedBitSet have both at least one true bit
	 * in the same position. Equivalently, you could call "and" and check
	 * whether there is a set bit, but intersects will run faster if you don't
	 * need the result of the "and" operation.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return whether they intersect
	 * @since 0.3.2
	 */
	public boolean intersects(final CompressedBitSet a)
	{
		final NonEmptyVirtualStorage nevs = new NonEmptyVirtualStorage();
		try
		{
			this.andToContainer(a, nevs);
		}
		catch (final NonEmptyVirtualStorage.NonEmptyException nee)
		{
			return true;
		}
		return false;
	}

	/**
	 * Iterator over the set bits (this is what most people will want to use to
	 * browse the content if they want an iterator). The location of the set
	 * bits is returned, in increasing order.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the int iterator
	 */
	public IntIterator intIterator()
	{
		return new IntIteratorImpl(this.getEWAHIterator());
	}

	/**
	 * Checks whether this bitmap is empty (has a cardinality of zero).
	 * 
	 * @return true if no bit is set
	 */
	@Override
	public boolean isEmpty()
	{
		return getFirstSetBit() < 0;
	}

	/**
	 * Iterates over the positions of the true values. This is similar to
	 * intIterator(), but it uses Java generics.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the iterator
	 */

	@Override
	public Iterator<Integer> iterator()
	{
		return new Iterator<Integer>() {

			private final IntIterator under = intIterator();

			@Override
			public boolean hasNext()
			{
				return this.under.hasNext();
			}

			@Override
			public Integer next()
			{
				return this.under.next();
			}

			@Override
			public void remove()
			{
				throw new UnsupportedOperationException("bitsets do not support remove");
			}
		};
	}

	@Override
	public int length()
	{
		return sizeInBits;
	}

	/**
	 * Negate (bitwise) the current bitmap. To get a negated copy, do
	 * CompressedBitSet x= ((CompressedBitSet) mybitmap.clone()); x.not();
	 * 
	 * The running time is proportional to the compressed size (as reported by
	 * sizeInBytes()).
	 * 
	 * Because this modifies the bitmap, this method is not thread-safe.
	 */

	@Override
	public void not()
	{
		final EWAHIterator i = this.getEWAHIterator();
		if (!i.hasNext())
		{
			return;
		}

		while (true)
		{
			final RunningLengthWord rlw1 = i.next();
			rlw1.setRunningBit(!rlw1.getRunningBit());
			final int nlw = rlw1.getNumberOfLiteralWords();
			for (int j = 0; j < nlw; ++j)
			{
				final int position = i.literalWords() + j;
				i.buffer().negateWord(position);
			}

			if (!i.hasNext())
			{// must potentially adjust the last
				// literal word
				final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
				if (usedBitsInLast == 0)
				{
					return;
				}

				if (rlw1.getNumberOfLiteralWords() == 0)
				{
					if ((rlw1.getRunningLength() > 0) && (rlw1.getRunningBit()))
					{
						if ((rlw1.getRunningLength() == 1) && (rlw1.position > 0))
						{
							// we need to prune ending
							final EWAHIterator j = this.getEWAHIterator();
							int newrlwpos = this.rlw.position;
							while (j.hasNext())
							{
								final RunningLengthWord r = j.next();
								if (r.position < rlw1.position)
								{
									newrlwpos = r.position;
								}
								else
								{
									break;
								}
							}
							this.rlw.position = newrlwpos;
							this.buffer.removeLastWord();
						}
						else
						{
							rlw1.setRunningLength(rlw1.getRunningLength() - 1);
						}
						this.insertLiteralWord((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
					}
					return;
				}
				i.buffer().andWord(i.literalWords() + rlw1.getNumberOfLiteralWords() - 1, (~0l) >>> (WORD_IN_BITS - usedBitsInLast));
				return;
			}
		}
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise OR values of the
	 * current bitmap with some other bitmap.
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the EWAH compressed bitmap
	 */

	@Override
	public CompressedBitSet or(final CompressedBitSet a)
	{
		final int size = this.buffer.sizeInWords() + a.buffer.sizeInWords();
		final CompressedBitSet container = new CompressedBitSet(size);
		orToContainer(a, container);
		swap(container);
		return this;
	}

	/**
	 * Returns the cardinality of the result of a bitwise OR of the values of
	 * the current bitmap with some other bitmap. Avoids allocating an
	 * intermediate bitmap to hold the result of the OR.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the cardinality
	 * @since 0.4.0
	 */
	public int orCardinality(final CompressedBitSet a)
	{
		final BitCounter counter = new BitCounter();
		orToContainer(a, counter);
		return counter.getCount();
	}

	/**
	 * Computes the bitwise or between the current bitmap and the bitmap "a".
	 * Stores the result in the container.
	 * 
	 * The current bitmap is not modified.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @param container
	 *            where we store the result
	 * @since 0.4.0
	 */
	public void orToContainer(final CompressedBitSet a, final BitmapStorage container)
	{
		container.clear();
		final EWAHIterator i = a.getEWAHIterator();
		final EWAHIterator j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while ((rlwi.size() > 0) && (rlwj.size() > 0))
		{
			while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0))
			{
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				if (predator.getRunningBit())
				{
					container.addStreamOfEmptyWords(true, predator.getRunningLength());
					prey.discardFirstWords(predator.getRunningLength());
				}
				else
				{
					final long index = prey.discharge(container, predator.getRunningLength());
					container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
				}
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if (nbre_literal > 0)
			{
				for (int k = 0; k < nbre_literal; ++k)
				{
					container.addWord(rlwi.getLiteralWordAt(k) | rlwj.getLiteralWordAt(k));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
		remaining.discharge(container);
		container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
	}

	@Override
	public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException
	{
		deserialize(in);
	}

	/**
	 * Iterator over the set bits in reverse order.
	 *
	 * The current bitmap is not modified.
	 *
	 * @return the int iterator
	 */
	public IntIterator reverseIntIterator()
	{
		return new ReverseIntIterator(this.getReverseEWAHIterator(), this.sizeInBits);
	}

	/**
	 * Serialize.
	 *
	 * The current bitmap is not modified.
	 *
	 * @param out
	 *            the DataOutput stream
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public void serialize(final DataOutput out) throws IOException
	{
		out.writeInt(this.sizeInBits);
		final int siw = this.buffer.sizeInWords();
		out.writeInt(siw);
		for (int i = 0; i < siw; ++i)
		{
			out.writeLong(this.buffer.getWord(i));
		}
		out.writeInt(this.rlw.position);
	}

	/**
	 * Report the number of bytes required to serialize this bitmap
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the size in bytes
	 */
	public int serializedSizeInBytes()
	{
		return this.sizeInBytes() + 3 * 4;
	}

	/**
	 * Set the bit at position i to true.
	 * 
	 * Though you can set the bits in any order (e.g., set(100), set(10),
	 * set(1), you will typically get better performance if you set the bits in
	 * increasing order (e.g., set(1), set(10), set(100)).
	 * 
	 * Setting a bit that is larger than any of the current set bit is a
	 * constant time operation. Setting a bit that is smaller than an already
	 * set bit can require time proportional to the compressed size of the
	 * bitmap, as the bitmap may need to be rewritten.
	 * 
	 * Since this modifies the bitmap, this method is not thread-safe.
	 *
	 * @param i
	 *            the index
	 * @return true if the value was set
	 * @throws IndexOutOfBoundsException
	 *             if i is negative or greater than Integer.MAX_VALUE - 64
	 */
	@Override
	public void set(final int i)
	{
		set(i, true);
	}

	/**
	 * For internal use.
	 *
	 * @param i
	 *            the index
	 * @param value
	 *            the value
	 */
	@Override
	public void set(final int i, final boolean value)
	{
		if ((i > Integer.MAX_VALUE - WORD_IN_BITS) || (i < 0))
		{
			throw new IndexOutOfBoundsException("Position should be between 0 and " + (Integer.MAX_VALUE - WORD_IN_BITS));
		}
		if (i < this.sizeInBits)
		{
			locateAndSet(i, value);
		}
		else
		{
			extendAndSet(i, value);
		}
	}

	/**
	 * Change the reported size in bits of the *uncompressed* bitmap represented
	 * by this compressed bitmap. It may change the underlying compressed
	 * bitmap. It is not possible to reduce the sizeInBits, but it can be
	 * extended. The new bits are set to false or true depending on the value of
	 * defaultValue.
	 *
	 * This method is not thread-safe.
	 *
	 * @param size
	 *            the size in bits
	 * @param defaultValue
	 *            the default boolean value
	 * @return true if the update was possible
	 */
	public boolean setSizeInBits(final int size, final boolean defaultValue)
	{
		if (size <= this.sizeInBits)
		{
			return false;
		}
		if ((this.sizeInBits % WORD_IN_BITS) != 0)
		{
			if (!defaultValue)
			{
				if (this.rlw.getNumberOfLiteralWords() > 0)
				{
					final int bitsToAdd = size - this.sizeInBits;
					final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
					final int freeBitsInLast = WORD_IN_BITS - usedBitsInLast;
					if (this.buffer.getLastWord() == 0l)
					{
						this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
						this.buffer.removeLastWord();
						this.sizeInBits -= usedBitsInLast;
					}
					else if (usedBitsInLast > 0)
					{
						this.sizeInBits += Math.min(bitsToAdd, freeBitsInLast);
					}
				}
			}
			else
			{
				if (this.rlw.getNumberOfLiteralWords() == 0)
				{
					this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
					insertLiteralWord(0);
				}
				final int maskWidth = Math.min(WORD_IN_BITS - this.sizeInBits % WORD_IN_BITS, size - this.sizeInBits);
				final int maskShift = this.sizeInBits % WORD_IN_BITS;
				final long mask = ((~0l) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
				this.buffer.orLastWord(mask);
				if (this.buffer.getLastWord() == ~0l)
				{
					this.buffer.removeLastWord();
					this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
					insertEmptyWord(true);
				}
				this.sizeInBits += maskWidth;
			}
		}
		this.addStreamOfEmptyWords(defaultValue, (size / WORD_IN_BITS) - (this.sizeInBits / WORD_IN_BITS));
		if (this.sizeInBits < size)
		{
			final int dist = distanceInWords(size - 1);
			if (dist > 0)
			{
				insertLiteralWord(0);
			}
			if (defaultValue)
			{
				final int maskWidth = size - this.sizeInBits;
				final int maskShift = this.sizeInBits % WORD_IN_BITS;
				final long mask = ((~0l) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
				this.buffer.orLastWord(mask);
			}
			this.sizeInBits = size;
		}
		return true;
	}

	@Override
	public void setSizeInBitsWithinLastWord(final int size)
	{
		// TODO: This method could be replaced with setSizeInBits
		if ((size + WORD_IN_BITS - 1) / WORD_IN_BITS > (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS)
		{
			setSizeInBits(size, false);
			return;
		}
		if ((size + WORD_IN_BITS - 1) / WORD_IN_BITS != (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS)
		{
			throw new RuntimeException("You can only reduce the size of the bitmap within the scope of the last word. To extend the bitmap, please call setSizeInBits(int,boolean).");
		}
		this.sizeInBits = size;
		final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
		if (usedBitsInLast == 0)
		{
			return;
		}
		if (this.rlw.getNumberOfLiteralWords() == 0)
		{
			if (this.rlw.getRunningLength() > 0)
			{
				this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
				final long word = this.rlw.getRunningBit() ? (~0l) >>> (WORD_IN_BITS - usedBitsInLast) : 0l;
				this.insertLiteralWord(word);
			}
			return;
		}
		this.buffer.andLastWord((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
	}

	/**
	 * Generate a new bitmap a new bitmap shifted by "b" bits. If b is positive,
	 * the position of all set bits is increased by b. The negative case is not
	 * supported.
	 *
	 * @param b
	 *            number of bits
	 * @return new shifted bitmap
	 */
	public CompressedBitSet shift(final int b)
	{
		if (b < 0)
		{
			throw new IllegalArgumentException("Negative shifts unsupported at the moment."); // TODO:
																								// add
		}
		// support
		final int sz = this.buffer.sizeInWords();
		final int newsz = b > 0 ? sz + (b + (WORD_IN_BITS - 1)) / WORD_IN_BITS : sz;
		final CompressedBitSet answer = new CompressedBitSet(newsz);
		final IteratingRLW i = this.getIteratingRLW();
		final int fullwords = b / WORD_IN_BITS;
		final int shift = b % WORD_IN_BITS;
		answer.addStreamOfEmptyWords(false, fullwords);
		if (shift == 0)
		{
			answer.buffer.push_back(this.buffer, 0, sz);
		}
		else
		{
			// whether the shift should justify a new word
			final boolean shiftextension = ((this.sizeInBits + WORD_IN_BITS - 1) % WORD_IN_BITS) + shift >= WORD_IN_BITS;
			long w = 0;
			while (true)
			{
				final long rl = i.getRunningLength();
				if (rl > 0)
				{
					if (i.getRunningBit())
					{
						final long sw = w | (-1l << shift);
						answer.addWord(sw);
						w = -1l >>> (WORD_IN_BITS - shift);
					}
					else
					{
						answer.addWord(w);
						w = 0;
					}
					if (rl > 1)
					{
						answer.addStreamOfEmptyWords(i.getRunningBit(), rl - 1);
					}
				}
				final int x = i.getNumberOfLiteralWords();
				for (int k = 0; k < x; ++k)
				{
					final long neww = i.getLiteralWordAt(k);
					final long sw = w | (neww << shift);
					answer.addWord(sw);
					w = neww >>> (WORD_IN_BITS - shift);
				}
				if (!i.next())
				{
					if (shiftextension)
					{
						answer.addWord(w);
					}
					break;
				}
			}
		}
		answer.sizeInBits = this.sizeInBits + b;
		return answer;
	}

	/**
	 * Returns the size in bits of the *uncompressed* bitmap represented by this
	 * compressed bitmap. Initially, the sizeInBits is zero. It is extended
	 * automatically when you set bits to true.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @return the size in bits
	 */

	@Override
	public int sizeInBits()
	{
		return this.sizeInBits;
	}

	/**
	 * Report the *compressed* size of the bitmap (equivalent to memory usage,
	 * after accounting for some overhead).
	 *
	 * @return the size in bytes
	 */

	@Override
	public int sizeInBytes()
	{
		return this.buffer.sizeInWords() * (WORD_IN_BITS / 8);
	}

	/**
	 * Swap the content of the bitmap with another.
	 *
	 * @param other
	 *            bitmap to swap with
	 */
	public void swap(final CompressedBitSet other)
	{
		this.buffer.swap(other.buffer);

		final int tmp2 = this.rlw.position;
		this.rlw.position = other.rlw.position;
		other.rlw.position = tmp2;

		final int tmp3 = this.sizeInBits;
		this.sizeInBits = other.sizeInBits;
		other.sizeInBits = tmp3;
	}

	/**
	 * Populate an array of (sorted integers) corresponding to the location of
	 * the set bits.
	 *
	 * @return the array containing the location of the set bits
	 */
	public int[] toArray()
	{
		final int[] ans = new int[this.cardinality()];
		int inAnsPos = 0;
		int pos = 0;
		final EWAHIterator i = this.getEWAHIterator();
		while (i.hasNext())
		{
			final RunningLengthWord localRlw = i.next();
			final long runningLength = localRlw.getRunningLength();
			if (localRlw.getRunningBit())
			{
				for (int j = 0; j < runningLength; ++j)
				{
					for (int c = 0; c < WORD_IN_BITS; ++c)
					{
						ans[inAnsPos++] = pos++;
					}
				}
			}
			else
			{
				pos += WORD_IN_BITS * runningLength;
			}
			final int numberOfLiteralWords = localRlw.getNumberOfLiteralWords();
			final int literalWords = i.literalWords();
			for (int j = 0; j < numberOfLiteralWords; ++j)
			{
				long data = i.buffer().getWord(literalWords + j);
				while (data != 0)
				{
					final long T = data & -data;
					ans[inAnsPos++] = Long.bitCount(T - 1) + pos;
					data ^= T;
				}
				pos += WORD_IN_BITS;
			}
		}
		return ans;

	}

	/**
	 * A more detailed string describing the bitmap (useful for debugging).
	 *
	 * @return the string
	 */
	public String toDebugString()
	{
		final StringBuilder ans = new StringBuilder();
		ans.append(" CompressedBitSet, size in bits = ");
		ans.append(this.sizeInBits).append(" size in words = ");
		ans.append(this.buffer.sizeInWords()).append("\n");
		final EWAHIterator i = this.getEWAHIterator();
		while (i.hasNext())
		{
			final RunningLengthWord localrlw = i.next();
			if (localrlw.getRunningBit())
			{
				ans.append(localrlw.getRunningLength()).append(" 1x11\n");
			}
			else
			{
				ans.append(localrlw.getRunningLength()).append(" 0x00\n");
			}
			ans.append(localrlw.getNumberOfLiteralWords()).append(" dirties\n");
			for (int j = 0; j < localrlw.getNumberOfLiteralWords(); ++j)
			{
				final long data = i.buffer().getWord(i.literalWords() + j);
				ans.append("\t").append(data).append("\n");
			}
		}
		return ans.toString();
	}

	/**
	 * Gets the locations of the true values as one list. (May use more memory
	 * than iterator().)
	 * 
	 * The current bitmap is not modified.
	 * 
	 * API change: prior to version 0.8.3, this method was called getPositions.
	 *
	 * @return the positions in a list
	 */
	public List<Integer> toList()
	{
		final ArrayList<Integer> v = new ArrayList<Integer>();
		final EWAHIterator i = this.getEWAHIterator();
		int pos = 0;
		while (i.hasNext())
		{
			final RunningLengthWord localrlw = i.next();
			if (localrlw.getRunningBit())
			{
				final long N = localrlw.getRunningLength();
				for (long j = 0; j < N; ++j)
				{
					for (int c = 0; c < WORD_IN_BITS; ++c)
					{
						v.add(pos++);
					}
				}
			}
			else
			{
				pos += WORD_IN_BITS * localrlw.getRunningLength();
			}
			final int nlw = localrlw.getNumberOfLiteralWords();
			for (int j = 0; j < nlw; ++j)
			{
				long data = i.buffer().getWord(i.literalWords() + j);
				while (data != 0)
				{
					final long T = data & -data;
					v.add(Long.bitCount(T - 1) + pos);
					data ^= T;
				}
				pos += WORD_IN_BITS;
			}
		}
		while ((v.size() > 0) && (v.get(v.size() - 1) >= this.sizeInBits))
		{
			v.remove(v.size() - 1);
		}
		return v;
	}

	/**
	 * A string describing the bitmap.
	 *
	 * @return the string
	 */

	@Override
	public String toString()
	{
		final StringBuilder answer = new StringBuilder();
		final IntIterator i = this.intIterator();
		answer.append("{");
		if (i.hasNext())
		{
			answer.append(i.next());
		}
		while (i.hasNext())
		{
			answer.append(",");
			answer.append(i.next());
		}
		answer.append("}");
		return answer.toString();
	}

	/**
	 * Reduce the internal buffer to its minimal allowable size. This can free
	 * memory.
	 */
	public void trim()
	{
		this.buffer.trim();
	}

	@Override
	public void writeExternal(final ObjectOutput out) throws IOException
	{
		serialize(out);
	}

	/**
	 * Returns a new compressed bitmap containing the bitwise XOR values of the
	 * current bitmap with some other bitmap.
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * If you are not planning on adding to the resulting bitmap, you may call
	 * the trim() method to reduce memory usage.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the EWAH compressed bitmap
	 */

	@Override
	public CompressedBitSet xor(final CompressedBitSet a)
	{
		final int size = this.buffer.sizeInWords() + a.buffer.sizeInWords();
		final CompressedBitSet container = new CompressedBitSet(size);
		xorToContainer(a, container);
		return container;
	}

	/**
	 * Returns the cardinality of the result of a bitwise XOR of the values of
	 * the current bitmap with some other bitmap. Avoids allocating an
	 * intermediate bitmap to hold the result of the OR.
	 * 
	 * The current bitmap is not modified.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @return the cardinality
	 * @since 0.4.0
	 */
	public int xorCardinality(final CompressedBitSet a)
	{
		final BitCounter counter = new BitCounter();
		xorToContainer(a, counter);
		return counter.getCount();
	}

	/**
	 * Computes a new compressed bitmap containing the bitwise XOR values of the
	 * current bitmap with some other bitmap.
	 * 
	 * The running time is proportional to the sum of the compressed sizes (as
	 * reported by sizeInBytes()).
	 * 
	 * The current bitmap is not modified.
	 * 
	 * The content of the container is overwritten.
	 *
	 * @param a
	 *            the other bitmap (it will not be modified)
	 * @param container
	 *            where we store the result
	 * @since 0.4.0
	 */
	public void xorToContainer(final CompressedBitSet a, final BitmapStorage container)
	{
		container.clear();
		final EWAHIterator i = a.getEWAHIterator();
		final EWAHIterator j = getEWAHIterator();
		final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
		final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
		while ((rlwi.size() > 0) && (rlwj.size() > 0))
		{
			while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0))
			{
				final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
				final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
				final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
				final long index = (!predator.getRunningBit()) ? prey.discharge(container, predator.getRunningLength()) : prey.dischargeNegated(container, predator.getRunningLength());
				container.addStreamOfEmptyWords(predator.getRunningBit(), predator.getRunningLength() - index);
				predator.discardRunningWords();
			}
			final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
			if (nbre_literal > 0)
			{
				for (int k = 0; k < nbre_literal; ++k)
				{
					container.addWord(rlwi.getLiteralWordAt(k) ^ rlwj.getLiteralWordAt(k));
				}
				rlwi.discardLiteralWords(nbre_literal);
				rlwj.discardLiteralWords(nbre_literal);
			}
		}
		final boolean i_remains = rlwi.size() > 0;
		final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
		remaining.discharge(container);
		container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
	}

	/**
	 * For internal use.
	 *
	 * @param i
	 *            the index
	 */
	private int distanceInWords(final int i)
	{
		return (i + WORD_IN_BITS) / WORD_IN_BITS - (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS;
	}

	/**
	 * For internal use.
	 *
	 * @param i
	 *            the index
	 * @param value
	 *            the value
	 */
	private void extendAndSet(final int i, final boolean value)
	{
		final int dist = distanceInWords(i);
		this.sizeInBits = i + 1;
		if (value)
		{
			if (dist > 0)
			{
				if (dist > 1)
				{
					fastaddStreamOfEmptyWords(false, dist - 1);
				}
				insertLiteralWord(1l << (i % WORD_IN_BITS));
				return;
			}
			if (this.rlw.getNumberOfLiteralWords() == 0)
			{
				this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
				insertLiteralWord(1l << (i % WORD_IN_BITS));
				return;
			}
			this.buffer.orLastWord(1l << (i % WORD_IN_BITS));
			if (this.buffer.getLastWord() == ~0l)
			{
				this.buffer.removeLastWord();
				this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
				// next we add one clean word
				insertEmptyWord(true);
			}
		}
		else
		{
			if (dist > 0)
			{
				fastaddStreamOfEmptyWords(false, dist);
			}
		}
	}

	/**
	 * For experts: You want to add many zeroes or ones faster?
	 * 
	 * This method does not update sizeInBits.
	 *
	 * @param v
	 *            the boolean value
	 * @param number
	 *            the number (must be greater than 0)
	 */
	private void fastaddStreamOfEmptyWords(final boolean v, long number)
	{
		if ((this.rlw.getRunningBit() != v) && (this.rlw.size() == 0))
		{
			this.rlw.setRunningBit(v);
		}
		else if ((this.rlw.getNumberOfLiteralWords() != 0) || (this.rlw.getRunningBit() != v))
		{
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if (v)
			{
				this.rlw.setRunningBit(true);
			}
		}

		final long runLen = this.rlw.getRunningLength();
		final long whatWeCanAdd = number < RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT - runLen ? number : RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT - runLen;
		this.rlw.setRunningLength(runLen + whatWeCanAdd);
		number -= whatWeCanAdd;

		while (number >= RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT)
		{
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if (v)
			{
				this.rlw.setRunningBit(true);
			}
			this.rlw.setRunningLength(RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT);
			number -= RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT;
		}
		if (number > 0)
		{
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			if (v)
			{
				this.rlw.setRunningBit(true);
			}
			this.rlw.setRunningLength(number);
		}
	}

	/**
	 * Gets a ReverseEWAHIterator over the data. This is a customized iterator
	 * which iterates over run length words in reverse order. For experts only.
	 *
	 * The current bitmap is not modified.
	 *
	 * @return the ReverseEWAHIterator
	 */
	private ReverseEWAHIterator getReverseEWAHIterator()
	{
		return new ReverseEWAHIterator(this.buffer);
	}

	/**
	 * For internal use.
	 *
	 * @param v
	 *            the boolean value
	 */
	private void insertEmptyWord(final boolean v)
	{
		final boolean noLiteralWords = (this.rlw.getNumberOfLiteralWords() == 0);
		final long runningLength = this.rlw.getRunningLength();
		if (noLiteralWords && runningLength == 0)
		{
			this.rlw.setRunningBit(v);
		}
		if (noLiteralWords && this.rlw.getRunningBit() == v && (runningLength < RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT))
		{
			this.rlw.setRunningLength(runningLength + 1);
			return;
		}
		this.buffer.push_back(0);
		this.rlw.position = this.buffer.sizeInWords() - 1;
		this.rlw.setRunningBit(v);
		this.rlw.setRunningLength(1);
	}

	/**
	 * For internal use.
	 *
	 * @param newData
	 *            the literal word
	 */
	private void insertLiteralWord(final long newData)
	{
		final int numberSoFar = this.rlw.getNumberOfLiteralWords();
		if (numberSoFar >= RunningLengthWord.LARGEST_LITERAL_COUNT)
		{
			this.buffer.push_back(0);
			this.rlw.position = this.buffer.sizeInWords() - 1;
			this.rlw.setNumberOfLiteralWords(1);
			this.buffer.push_back(newData);
		}
		else
		{
			this.rlw.setNumberOfLiteralWords(numberSoFar + 1);
			this.buffer.push_back(newData);
		}
	}

	/**
	 * For internal use.
	 *
	 * @param i
	 *            the index
	 * @param value
	 *            the value
	 */
	private void locateAndSet(final int i, final boolean value)
	{
		int nbits = 0;
		final int siw = this.buffer.sizeInWords();
		for (int pos = 0; pos < siw;)
		{
			final long rl = RunningLengthWord.getRunningLength(this.buffer, pos);
			final boolean rb = RunningLengthWord.getRunningBit(this.buffer, pos);
			final long lw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, pos);
			final long rbits = rl * WORD_IN_BITS;
			if (i < nbits + rbits)
			{
				setInRunningLength(value, i, nbits, pos, rl, rb, lw);
				return;
			}
			nbits += rbits;
			final long lbits = lw * WORD_IN_BITS;
			if (i < nbits + lbits)
			{
				setInLiteralWords(value, i, nbits, pos, rl, rb, lw);
				return;
			}
			nbits += lbits;
			pos += lw + 1;
		}
	}

	private boolean mergeLiteralWordInNextRunningLength(final boolean value, final long lw, final int pos, final int wordPosition)
	{
		final int nextRLWPos = (int)(pos + lw + 1);
		if (lw == wordPosition && nextRLWPos < this.buffer.sizeInWords())
		{
			final long nextRl = RunningLengthWord.getRunningLength(this.buffer, nextRLWPos);
			final boolean nextRb = RunningLengthWord.getRunningBit(this.buffer, nextRLWPos);
			return (value == nextRb || nextRl == 0);
		}
		return false;
	}

	private void setInLiteralWords(final boolean value, final int i, final int nbits, final int pos, final long rl, final boolean rb, final long lw)
	{
		final int wordPosition = (i - nbits) / WORD_IN_BITS + 1;
		final long mask = 1l << i % WORD_IN_BITS;
		if (value)
		{
			this.buffer.orWord(pos + wordPosition, mask);
		}
		else
		{
			this.buffer.andWord(pos + wordPosition, ~mask);
		}
		final long emptyWord = value ? ~0l : 0l;
		if (this.buffer.getWord(pos + wordPosition) == emptyWord)
		{
			final boolean canMergeInCurrentRLW = mergeLiteralWordInCurrentRunningLength(value, rb, rl, wordPosition);
			final boolean canMergeInNextRLW = mergeLiteralWordInNextRunningLength(value, lw, pos, wordPosition);
			if (canMergeInCurrentRLW && canMergeInNextRLW)
			{
				final long nextRl = RunningLengthWord.getRunningLength(this.buffer, pos + 2);
				final long nextLw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, pos + 2);
				this.buffer.collapse(pos, 2);
				setRLWInfo(pos, value, rl + 1 + nextRl, nextLw);
				if (this.rlw.position >= pos + 2)
				{
					this.rlw.position -= 2;
				}
			}
			else if (canMergeInCurrentRLW)
			{
				this.buffer.collapse(pos + 1, 1);
				setRLWInfo(pos, value, rl + 1, lw - 1);
				if (this.rlw.position >= pos + 2)
				{
					this.rlw.position--;
				}
			}
			else if (canMergeInNextRLW)
			{
				final int nextRLWPos = (int)(pos + lw + 1);
				final long nextRl = RunningLengthWord.getRunningLength(this.buffer, nextRLWPos);
				final long nextLw = RunningLengthWord.getNumberOfLiteralWords(this.buffer, nextRLWPos);
				this.buffer.collapse(pos + wordPosition, 1);
				setRLWInfo(pos, rb, rl, lw - 1);
				setRLWInfo(pos + wordPosition, value, nextRl + 1, nextLw);
				if (this.rlw.position >= nextRLWPos)
				{
					this.rlw.position -= lw + 1 - wordPosition;
				}
			}
			else
			{
				setRLWInfo(pos, rb, rl, wordPosition - 1);
				setRLWInfo(pos + wordPosition, value, 1l, lw - wordPosition);
				if (this.rlw.position == pos)
				{
					this.rlw.position += wordPosition;
				}
			}
		}
	}

	private void setInRunningLength(final boolean value, final int i, final int nbits, final int pos, final long rl, final boolean rb, final long lw)
	{
		if (value != rb)
		{
			final int wordPosition = (i - nbits) / WORD_IN_BITS + 1;
			final int addedWords = (wordPosition == rl) ? 1 : 2;
			this.buffer.expand(pos + 1, addedWords);
			final long mask = 1l << i % WORD_IN_BITS;
			this.buffer.setWord(pos + 1, value ? mask : ~mask);
			if (this.rlw.position >= pos + 1)
			{
				this.rlw.position += addedWords;
			}
			if (addedWords == 1)
			{
				setRLWInfo(pos, rb, rl - 1, lw + 1);
			}
			else
			{
				setRLWInfo(pos, rb, wordPosition - 1, 1l);
				setRLWInfo(pos + 2, rb, rl - wordPosition, lw);
				if (this.rlw.position == pos)
				{
					this.rlw.position += 2;
				}
			}
		}
	}

	private void setRLWInfo(final int pos, final boolean rb, final long rl, final long lw)
	{
		RunningLengthWord.setRunningBit(this.buffer, pos, rb);
		RunningLengthWord.setRunningLength(this.buffer, pos, rl);
		RunningLengthWord.setNumberOfLiteralWords(this.buffer, pos, lw);
	}

}