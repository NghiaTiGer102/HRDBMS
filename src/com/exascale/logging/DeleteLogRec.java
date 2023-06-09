package com.exascale.logging;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import com.exascale.filesystem.Block;
import com.exascale.filesystem.Page;
import com.exascale.managers.BufferManager;
import com.exascale.managers.HRDBMSWorker;

public class DeleteLogRec extends LogRec
{
	private static Charset cs = StandardCharsets.UTF_8;
	private static sun.misc.Unsafe unsafe;
	private static long offset;

	static
	{
		try
		{
			final Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			unsafe = (sun.misc.Unsafe)f.get(null);
			final Field fieldToUpdate = String.class.getDeclaredField("value");
			// get unsafe offset to this field
			offset = unsafe.objectFieldOffset(fieldToUpdate);
		}
		catch (final Exception e)
		{
			unsafe = null;
		}
	}
	private Block b;
	private int off;
	private byte[] before;
	private byte[] after;

	private final CharsetEncoder ce = cs.newEncoder();

	public DeleteLogRec(final long txnum, final Block b, final int off, final byte[] before, final byte[] after) throws Exception
	{
		super(LogRec.DELETE, txnum, ByteBuffer.allocate(32 + b.toString().getBytes(StandardCharsets.UTF_8).length + 8 + 2 * before.length));

		if (before.length != after.length)
		{
			throw new Exception("Before and after images length do not match");
		}

		this.b = b;
		this.off = off;
		this.before = before;
		this.after = after;

		final ByteBuffer buff = this.buffer();
		buff.position(28);
		// byte[] bbytes = b.toString().getBytes("UTF-8");
		final String string = b.toString();
		final byte[] ba = new byte[string.length() << 2];
		final char[] value = (char[])unsafe.getObject(string, offset);
		final int blen = ((sun.nio.cs.ArrayEncoder)ce).encode(value, 0, value.length, ba);
		final byte[] bbytes = Arrays.copyOf(ba, blen);
		buff.putInt(blen);
		try
		{
			buff.put(bbytes);
		}
		catch (final Exception e)
		{
			HRDBMSWorker.logger.error("Error converting bytes to UTF-8 string in DeleteLogRec constructor.", e);
			return;
		}

		buff.putInt(before.length);
		buff.put(before);
		buff.put(after);
		buff.putInt(off);
	}

	public byte[] getAfter()
	{
		return after;
	}

	public byte[] getBefore()
	{
		return before;
	}

	public Block getBlock()
	{
		return b;
	}

	@Override
	public int getEnd()
	{
		return off + before.length;
	}

	@Override
	public int getOffset()
	{
		return off;
	}

	@Override
	public void redo() throws Exception
	{
		// HRDBMSWorker.logger.debug("Redoing change at " + b + "@" + off +
		// " for a length of " + before.length);
		if (b.number() < 0)
		{
			final Exception e = new Exception("Negative block number requested: " + b.number());
			HRDBMSWorker.logger.debug("", e);
		}

		BufferManager.requestPage(b, txnum());

		Page p = null;
		while (p == null)
		{
			p = BufferManager.getPage(b, txnum());
		}

		p.write(off, after, this.txnum(), this.lsn());
		p.unpin(this.txnum());
	}

	@Override
	public void undo() throws Exception
	{
		// HRDBMSWorker.logger.debug("Undoing change at " + b + "@" + off +
		// " for a length of " + before.length);
		if (b.number() < 0)
		{
			final Exception e = new Exception("Negative block number requested: " + b.number());
			HRDBMSWorker.logger.debug("", e);
		}

		BufferManager.requestPage(b, txnum());

		Page p = null;
		while (p == null)
		{
			p = BufferManager.getPage(b, txnum());
		}

		p.write(off, before, this.txnum(), this.lsn());
		p.unpin(this.txnum());
	}
}