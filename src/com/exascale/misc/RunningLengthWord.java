package com.exascale.misc;

public final class RunningLengthWord implements Cloneable
{

	/**
	 * number of bits dedicated to marking of the running length of clean words
	 */
	public static final int RUNNING_LENGTH_BITS = 32;

	private static final int LITERAL_BITS = 64 - 1 - RUNNING_LENGTH_BITS;

	/**
	 * largest number of literal words in a run.
	 */
	public static final int LARGEST_LITERAL_COUNT = (1 << LITERAL_BITS) - 1;

	/**
	 * largest number of clean words in a run
	 */
	public static final long LARGEST_RUNNING_LENGTH_COUNT = (1l << RUNNING_LENGTH_BITS) - 1;

	private static final long RUNNING_LENGTH_PLUS_RUNNING_BIT = (1l << (RUNNING_LENGTH_BITS + 1)) - 1;

	private static final long SHIFTED_LARGEST_RUNNING_LENGTH_COUNT = LARGEST_RUNNING_LENGTH_COUNT << 1;

	private static final long NOT_RUNNING_LENGTH_PLUS_RUNNING_BIT = ~RUNNING_LENGTH_PLUS_RUNNING_BIT;

	private static final long NOT_SHIFTED_LARGEST_RUNNING_LENGTH_COUNT = ~SHIFTED_LARGEST_RUNNING_LENGTH_COUNT;

	/**
	 * The array of words.
	 */
	final Buffer buffer;

	/**
	 * The position in array.
	 */
	int position;

	/**
	 * Instantiates a new running length word.
	 *
	 * @param buffer
	 *            the buffer
	 * @param p
	 *            position in the buffer where the running length word is
	 *            located.
	 */
	RunningLengthWord(final Buffer buffer, final int p)
	{
		this.buffer = buffer;
		this.position = p;
	}

	static int getNumberOfLiteralWords(final Buffer buffer, final int position)
	{
		return (int)(buffer.getWord(position) >>> (1 + RUNNING_LENGTH_BITS));
	}

	static boolean getRunningBit(final Buffer buffer, final int position)
	{
		return (buffer.getWord(position) & 1) != 0;
	}

	static long getRunningLength(final Buffer buffer, final int position)
	{
		return (buffer.getWord(position) >>> 1) & LARGEST_RUNNING_LENGTH_COUNT;
	}

	static void setNumberOfLiteralWords(final Buffer buffer, final int position, final long number)
	{
		buffer.orWord(position, NOT_RUNNING_LENGTH_PLUS_RUNNING_BIT);
		buffer.andWord(position, (number << (RUNNING_LENGTH_BITS + 1)) | RUNNING_LENGTH_PLUS_RUNNING_BIT);
	}

	static void setRunningBit(final Buffer buffer, final int position, final boolean b)
	{
		if (b)
		{
			buffer.orWord(position, 1l);
		}
		else
		{
			buffer.andWord(position, ~1l);
		}
	}

	static void setRunningLength(final Buffer buffer, final int position, final long number)
	{
		buffer.orWord(position, SHIFTED_LARGEST_RUNNING_LENGTH_COUNT);
		buffer.andWord(position, (number << 1) | NOT_SHIFTED_LARGEST_RUNNING_LENGTH_COUNT);
	}

	@Override
	public RunningLengthWord clone() throws CloneNotSupportedException
	{
		return (RunningLengthWord)super.clone();
	}

	/**
	 * Gets the number of literal words.
	 *
	 * @return the number of literal words
	 */
	public int getNumberOfLiteralWords()
	{
		return getNumberOfLiteralWords(this.buffer, this.position);
	}

	/**
	 * Gets the running bit.
	 *
	 * @return the running bit
	 */
	public boolean getRunningBit()
	{
		return getRunningBit(this.buffer, this.position);
	}

	/**
	 * Gets the running length.
	 *
	 * @return the running length
	 */
	public long getRunningLength()
	{
		return getRunningLength(this.buffer, this.position);
	}

	/**
	 * Sets the number of literal words.
	 *
	 * @param number
	 *            the new number of literal words
	 */
	public void setNumberOfLiteralWords(final long number)
	{
		setNumberOfLiteralWords(this.buffer, this.position, number);
	}

	/**
	 * Sets the running bit.
	 *
	 * @param b
	 *            the new running bit
	 */
	public void setRunningBit(final boolean b)
	{
		setRunningBit(this.buffer, this.position, b);
	}

	/**
	 * Sets the running length.
	 *
	 * @param number
	 *            the new running length
	 */
	public void setRunningLength(final long number)
	{
		setRunningLength(this.buffer, this.position, number);
	}

	/**
	 * Return the size in uncompressed words represented by this running length
	 * word.
	 *
	 * @return the size
	 */
	public long size()
	{
		return getRunningLength() + getNumberOfLiteralWords();
	}

	/*
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString()
	{
		return "running bit = " + getRunningBit() + " running length = " + getRunningLength() + " number of lit. words " + getNumberOfLiteralWords();
	}

}