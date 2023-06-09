package com.exascale.misc;

public class BufferedIterator implements IteratingRLW, Cloneable
{

	private IteratingBufferedRunningLengthWord iteratingBrlw;

	private CloneableIterator<EWAHIterator> masterIterator;

	/**
	 * Instantiates a new iterating buffered running length word.
	 *
	 * @param iterator
	 *            iterator
	 */
	public BufferedIterator(final CloneableIterator<EWAHIterator> iterator)
	{
		this.masterIterator = iterator;
		if (this.masterIterator.hasNext())
		{
			iteratingBrlw = new IteratingBufferedRunningLengthWord(this.masterIterator.next());
		}
	}

	@Override
	public BufferedIterator clone() throws CloneNotSupportedException
	{
		final BufferedIterator answer = (BufferedIterator)super.clone();
		answer.iteratingBrlw = this.iteratingBrlw.clone();
		answer.masterIterator = this.masterIterator.clone();
		return answer;
	}

	/**
	 * Discard first words, iterating to the next running length word if needed.
	 *
	 * @param x
	 *            the number of words to be discarded
	 */
	@Override
	public void discardFirstWords(long x)
	{
		while (x > 0)
		{
			if (this.iteratingBrlw.getRunningLength() > x)
			{
				this.iteratingBrlw.discardFirstWords(x);
				return;
			}
			this.iteratingBrlw.discardFirstWords(this.iteratingBrlw.getRunningLength());
			x -= this.iteratingBrlw.getRunningLength();
			final long toDiscard = x > this.iteratingBrlw.getNumberOfLiteralWords() ? this.iteratingBrlw.getNumberOfLiteralWords() : x;

			this.iteratingBrlw.discardFirstWords(toDiscard);
			x -= toDiscard;
			if ((x > 0) || (this.iteratingBrlw.size() == 0))
			{
				if (!this.next())
				{
					break;
				}
			}
		}
	}

	@Override
	public void discardLiteralWords(final long x)
	{
		this.iteratingBrlw.discardLiteralWords(x);
		if (this.iteratingBrlw.getNumberOfLiteralWords() == 0)
		{
			this.next();
		}
	}

	@Override
	public void discardRunningWords()
	{
		this.iteratingBrlw.discardRunningWords();
		if (this.iteratingBrlw.getNumberOfLiteralWords() == 0)
		{
			this.next();
		}
	}

	/**
	 * Get the nth literal word for the current running length word
	 *
	 * @param index
	 *            zero based index
	 * @return the literal word
	 */
	@Override
	public long getLiteralWordAt(final int index)
	{
		return this.iteratingBrlw.getLiteralWordAt(index);
	}

	/**
	 * Gets the number of literal words for the current running length word.
	 *
	 * @return the number of literal words
	 */
	@Override
	public int getNumberOfLiteralWords()
	{
		return this.iteratingBrlw.getNumberOfLiteralWords();
	}

	/**
	 * Gets the running bit.
	 *
	 * @return the running bit
	 */
	@Override
	public boolean getRunningBit()
	{
		return this.iteratingBrlw.getRunningBit();
	}

	/**
	 * Gets the running length.
	 *
	 * @return the running length
	 */
	@Override
	public long getRunningLength()
	{
		return this.iteratingBrlw.getRunningLength();
	}

	/**
	 * Move to the next RunningLengthWord
	 *
	 * @return whether the move was possible
	 */
	@Override
	public boolean next()
	{
		if (!this.iteratingBrlw.next())
		{
			if (!this.masterIterator.hasNext())
			{
				return false;
			}
			else
			{
				this.iteratingBrlw = new IteratingBufferedRunningLengthWord(this.masterIterator.next());
			}
		}
		return true;
	}

	/**
	 * Size in uncompressed words of the current running length word.
	 *
	 * @return the size
	 */
	@Override
	public long size()
	{
		return this.iteratingBrlw.size();
	}

}