package com.exascale.misc;

import static com.exascale.misc.CompressedBitSet.WORD_IN_BITS;

/**
 * The IntIteratorImpl is the 64 bit implementation of the IntIterator
 * interface, which efficiently returns the stream of integers represented by an
 * EWAHIterator.
 *
 * @author Colby Ranger
 * @since 0.5.6
 */
final class IntIteratorImpl implements IntIterator
{

	private final EWAHIterator ewahIter;
	private final Buffer buffer;
	private int position;
	private int runningLength;
	private long word;
	private int wordPosition;
	private int wordLength;
	private int literalPosition;
	private boolean hasNext;

	IntIteratorImpl(final EWAHIterator ewahIter)
	{
		this.ewahIter = ewahIter;
		this.buffer = ewahIter.buffer();
		this.hasNext = this.moveToNext();
	}

	@Override
	public boolean hasNext()
	{
		return this.hasNext;
	}

	public boolean moveToNext()
	{
		while (!runningHasNext() && !literalHasNext())
		{
			if (!this.ewahIter.hasNext())
			{
				return false;
			}
			setRunningLengthWord(this.ewahIter.next());
		}
		return true;
	}

	@Override
	public int next()
	{
		final int answer;
		if (runningHasNext())
		{
			answer = this.position++;
		}
		else
		{
			final long t = this.word & -this.word;
			answer = this.literalPosition + Long.bitCount(t - 1);
			this.word ^= t;
		}
		this.hasNext = this.moveToNext();
		return answer;
	}

	private boolean literalHasNext()
	{
		while (this.word == 0 && this.wordPosition < this.wordLength)
		{
			this.word = this.buffer.getWord(this.wordPosition++);
			this.literalPosition = this.position;
			this.position += WORD_IN_BITS;
		}
		return this.word != 0;
	}

	private boolean runningHasNext()
	{
		return this.position < this.runningLength;
	}

	private void setRunningLengthWord(final RunningLengthWord rlw)
	{
		this.runningLength = WORD_IN_BITS * (int)rlw.getRunningLength() + this.position;
		if (!rlw.getRunningBit())
		{
			this.position = this.runningLength;
		}

		this.wordPosition = this.ewahIter.literalWords();
		this.wordLength = this.wordPosition + rlw.getNumberOfLiteralWords();
	}
}