package com.exascale.logging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;
import com.exascale.managers.HRDBMSWorker;
import com.exascale.managers.LogManager;
import com.exascale.tables.Transaction;

public class LogIterator implements Iterator<LogRec>
{
	private long nextpos;
	private final ByteBuffer sizeBuff = ByteBuffer.allocate(4);
	private final FileChannel fc;
	private int size;

	public LogIterator(final String filename) throws IOException
	{
		// synchronized (LogManager.noArchiveLock) // disable archiving while we
		// have
		// an iterator open
		synchronized (Transaction.txListLock)
		{
			// Transaction.txListLock.lock();
			LogManager.openIters++;
			LogManager.noArchive = true;
		}
		// Transaction.txListLock.unlock();

		final LinkedBlockingDeque<LogRec> log = LogManager.logs.get(filename);
		synchronized (log)
		{
			if (log.size() > 0)
			{
				LogManager.flush(log.getLast().lsn(), filename);
			}
		}

		fc = LogManager.getFile(filename);
		synchronized (fc)
		{
			try
			{
				fc.position(fc.size() - 4); // trailing log rec size
				sizeBuff.position(0);
				fc.read(sizeBuff);
				sizeBuff.position(0);
				size = sizeBuff.getInt();
				nextpos = fc.size() - 4 - size;
			}
			catch (final IllegalArgumentException e)
			{
				nextpos = -1;
			}
		}
	}

	public LogIterator(final String filename, final boolean flush) throws IOException
	{
		// synchronized (LogManager.noArchiveLock) // disable archiving while we
		// have
		// an iterator open
		synchronized (Transaction.txListLock)
		{
			LogManager.openIters++;
			LogManager.noArchive = true;
		}
		// Transaction.txListLock.unlock();

		fc = LogManager.getFile(filename);
		synchronized (fc)
		{
			try
			{
				fc.position(fc.size() - 4); // trailing log rec size
				sizeBuff.position(0);
				fc.read(sizeBuff);
				sizeBuff.position(0);
				size = sizeBuff.getInt();
				nextpos = fc.size() - 4 - size;
			}
			catch (final IllegalArgumentException e)
			{
				nextpos = -1;
			}
		}
	}

	public LogIterator(final String filename, final boolean flush, final FileChannel fc) throws IOException
	{
		// synchronized (LogManager.noArchiveLock) // disable archiving while we
		// have
		// an iterator open
		// synchronized(Transaction.txList)
		// {
		// LogManager.openIters++;
		// LogManager.noArchive = true;
		// }

		this.fc = fc;
		synchronized (fc)
		{
			try
			{
				fc.position(fc.size() - 4); // trailing log rec size
				sizeBuff.position(0);
				fc.read(sizeBuff);
				sizeBuff.position(0);
				size = sizeBuff.getInt();
				nextpos = fc.size() - 4 - size;
			}
			catch (final IllegalArgumentException e)
			{
				nextpos = -1;
			}
		}
	}

	public void close()
	{
		// synchronized (LogManager.noArchiveLock)
		// Transaction.txListLock.lock();
		synchronized (Transaction.txListLock)
		{
			LogManager.openIters--;

			if (LogManager.openIters == 0)
			{
				LogManager.noArchive = false;
			}
		}
		// Transaction.txListLock.unlock();
	}

	@Override
	public boolean hasNext()
	{
		return nextpos > 0;
	}

	@Override
	public LogRec next()
	{
		LogRec retval = null;
		try
		{
			synchronized (fc)
			{
				fc.position(nextpos);
				// HRDBMSWorker.logger.debug("Reading log rec at position = " +
				// nextpos);
				retval = new LogRec(fc);
				try
				{
					fc.position(nextpos - 8);
					sizeBuff.position(0);
					fc.read(sizeBuff);
					sizeBuff.position(0);
					size = sizeBuff.getInt();
					nextpos = fc.position() - 4 - size;
				}
				catch (final IllegalArgumentException e)
				{
					nextpos = -1;
				}
			}
		}
		catch (final IOException e)
		{
			HRDBMSWorker.logger.error("Exception occurred in LogIterator.next().", e);
			return null;
		}

		return retval;
	}

	@Override
	public void remove()
	{
		throw new UnsupportedOperationException();
	}
}
