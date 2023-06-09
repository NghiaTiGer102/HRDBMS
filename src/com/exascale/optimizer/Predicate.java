package com.exascale.optimizer;

public class Predicate
{
	private Expression lhs;
	private String op;
	private Expression rhs;

	public Predicate(final Expression lhs, final String op, final Expression rhs)
	{
		this.lhs = lhs;
		this.op = op;
		this.rhs = rhs;
	}

	protected Predicate()
	{
	}
	
	public String toString()
	{
		return "lhs " + op + " rhs";
	}

	@Override
	public Predicate clone()
	{
		Expression lClone = null;
		Expression rClone = null;

		if (lhs != null)
		{
			lClone = lhs.clone();
		}

		if (rhs != null)
		{
			rClone = rhs.clone();
		}

		return new Predicate(lClone, op, rClone);
	}

	@Override
	public boolean equals(final Object o)
	{
		if (!(o instanceof Predicate))
		{
			return false;
		}

		final Predicate rhs2 = (Predicate)o;
		return lhs.equals(rhs2.lhs) && op.equals(rhs2.op) && rhs.equals(rhs2.rhs);
	}

	public Expression getLHS()
	{
		return lhs;
	}

	public String getOp()
	{
		return op;
	}

	public Expression getRHS()
	{
		return rhs;
	}

	@Override
	public int hashCode()
	{
		int hash = 23;
		hash = hash * 31 + lhs.hashCode();
		hash = hash * 31 + op.hashCode();
		hash = hash * 31 + rhs.hashCode();
		return hash;
	}
	
	public void negate()
	{
		if (op.equals("E"))
		{
			op = "NE";
		}
		else if (op.equals("NE"))
		{
			op = "E";
		}
		else if (op.equals("G"))
		{
			op = "LE";
		}
		else if (op.equals("GE"))
		{
			op = "L";
		}
		else if (op.equals("L"))
		{
			op = "GE";
		}
		else if (op.equals("LE"))
		{
			op = "G";
		}
		else if (op.equals("LI"))
		{
			op = "NL";
		}
		else if (op.equals("NL"))
		{
			op = "LI";
		}
		else if (op.equals("IN"))
		{
			op = "NI";
		}
		else if (op.equals("NI"))
		{
			op = "IN";
		}
	}
}
