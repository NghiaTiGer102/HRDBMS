package com.exascale.tables;

import java.util.ArrayList;

public class InternalResultSet
{
	private final ArrayList<ArrayList<Object>> data;
	private int pos = -1;

	public InternalResultSet(final ArrayList<ArrayList<Object>> data)
	{
		this.data = data;
	}

	public Integer getInt(final int colPos)
	{
		return (Integer)(data.get(pos).get(colPos - 1));
	}

	public String getString(final int colPos)
	{
		return (String)(data.get(pos).get(colPos - 1));
	}

	public boolean next()
	{
		pos++;
		if (pos < data.size())
		{
			return true;
		}
		else
		{
			return false;
		}
	}
}
