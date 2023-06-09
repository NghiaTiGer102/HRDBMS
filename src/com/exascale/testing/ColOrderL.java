package com.exascale.testing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

public class ColOrderL
{
	private static final Object lock = new Object();
	private static int lowScore = Integer.MAX_VALUE;
	private static ArrayList<Integer> lowOrder = null;

	public static void main(final String[] args)
	{
		final ArrayList<ArrayList<Integer>> accesses = new ArrayList<ArrayList<Integer>>();
		ArrayList<Integer> access = new ArrayList<Integer>();
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(7);
		access.add(8);
		access.add(9);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(11);
		access.add(12);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(5);
		access.add(6);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(2);
		access.add(5);
		access.add(6);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(1);
		access.add(2);
		access.add(4);
		access.add(5);
		access.add(6);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(5);
		access.add(6);
		access.add(8);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(10);
		access.add(11);
		access.add(12);
		access.add(14);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(2);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(2);
		access.add(5);
		access.add(6);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(4);
		access.add(5);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(4);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(4);
		access.add(5);
		access.add(6);
		access.add(13);
		access.add(14);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(1);
		access.add(2);
		access.add(4);
		access.add(10);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(11);
		access.add(12);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		accesses.add(access);
		access = new ArrayList<Integer>();
		access.add(0);
		access.add(2);
		access.add(11);
		access.add(12);
		accesses.add(access);

		int start = 0;
		while (start < 15)
		{
			final ArrayList<Thread> threads = new ArrayList<Thread>();
			boolean epFirst = true;
			int i = 0;
			while (i < 16)
			{
				if (i != 5 && i != 6)
				{
					final Thread thread = new DoItThread(i, start, epFirst, accesses);
					threads.add(thread);
					thread.start();
				}

				i++;
			}

			for (final Thread thread : threads)
			{
				while (true)
				{
					try
					{
						thread.join();
						break;
					}
					catch (final Exception e)
					{
					}
				}
			}

			threads.clear();
			epFirst = false;
			i = 0;
			while (i < 16)
			{
				if (i != 5 && i != 6)
				{
					final Thread thread = new DoItThread(i, start, epFirst, accesses);
					threads.add(thread);
					thread.start();
				}

				i++;
			}

			for (final Thread thread : threads)
			{
				while (true)
				{
					try
					{
						thread.join();
						break;
					}
					catch (final Exception e)
					{
					}
				}
			}

			start++;
		}
	}

	private static void displayResults(final String table, final ArrayList<Integer> result)
	{
		String out = table + " = COLORDER(" + (result.get(0) + 1);
		int i = 1;
		while (i < result.size())
		{
			out += ("," + (result.get(i++) + 1));
		}

		out += ")";

		System.out.println(out);
	}

	private static ArrayList<Integer> doIt(final int first, final int start, final boolean epFirst, final ArrayList<ArrayList<Integer>> accesses)
	{
		final ArrayList<Integer> cols = new ArrayList<Integer>();
		int j = 0;
		while (j < 16)
		{
			if (j != 5 && j != 6 && j != first)
			{
				cols.add(j);
			}

			j++;
		}

		final PermIterator permIter = new PermIterator(cols);

		while (permIter.hasNext())
		{
			final ArrayList<Integer> temp = permIter.next();
			temp.add(0, first);
			final ArrayList<Integer> order = new ArrayList<Integer>();
			int i = 0;
			while (i < start)
			{
				order.add(temp.get(i++));
			}

			if (epFirst)
			{
				order.add(5);
				order.add(6);
			}
			else
			{
				order.add(6);
				order.add(5);
			}

			while (i < temp.size())
			{
				order.add(temp.get(i++));
			}

			final int score = score(order, accesses);

			synchronized (lock)
			{
				if (score < lowScore)
				{
					lowScore = score;
					lowOrder = order;
					System.out.println("New low score = " + score);
					displayResults("LINEITEM", lowOrder);
				}
			}
		}

		return lowOrder;
	}

	private static int score(final ArrayList<Integer> order, final ArrayList<ArrayList<Integer>> accesses)
	{
		final ArrayList<Integer> disk = new ArrayList<Integer>();
		disk.add(-1);
		disk.addAll(order);
		int copies = 1;

		if (disk.size() % 3 == 1)
		{
			disk.addAll(order);
			copies++;
		}
		else
		{
			while (disk.size() % 3 != 1)
			{
				disk.addAll(order);
				copies++;
			}
		}

		int score = 0;
		for (final ArrayList<Integer> access : accesses)
		{
			final HashSet<Integer> sbs = new HashSet<Integer>();
			for (final int col : access)
			{
				int found = 0;
				int i = 1;
				while (found < copies)
				{
					try
					{
						if (disk.get(i) == col)
						{
							final int sb = i / 3;
							sbs.add(sb);
							found++;
						}
					}
					catch (final Exception e)
					{
						System.out.println("Looking for " + col + " in " + disk);
						System.out.println("Found " + found + " instances");
						System.out.println("But there should be " + copies);
					}

					i++;
				}
			}

			score += sbs.size();
		}

		return score;
	}

	private static class DoItThread extends Thread
	{
		private final int first;
		private final int start;
		private final boolean epFirst;
		private final ArrayList<ArrayList<Integer>> accesses;

		public DoItThread(final int first, final int start, final boolean epFirst, final ArrayList<ArrayList<Integer>> accesses)
		{
			this.first = first;
			this.start = start;
			this.epFirst = epFirst;
			this.accesses = accesses;
		}

		@Override
		public void run()
		{
			doIt(first, start, epFirst, accesses);
		}
	}

	private static class PermIterator implements Iterator<ArrayList<Integer>>
	{
		private int[] next = null;

		private final int n;
		private int[] perm;
		private int[] dirs;
		private final ArrayList<Integer> initial;

		public PermIterator(final ArrayList<Integer> initial)
		{
			this.initial = initial;
			n = initial.size();
			if (n <= 0)
			{
				perm = (dirs = null);
			}
			else
			{
				perm = new int[n];
				dirs = new int[n];
				for (int i = 0; i < n; i++)
				{
					perm[i] = i;
					dirs[i] = -1;
				}
				dirs[0] = 0;
			}

			next = perm;
		}

		@Override
		public boolean hasNext()
		{
			return (makeNext() != null);
		}

		@Override
		public ArrayList<Integer> next()
		{
			final int[] r = makeNext();
			next = null;
			final ArrayList<Integer> retval = new ArrayList<Integer>();
			for (final int index : r)
			{
				retval.add(initial.get(index));
			}

			return retval;
		}

		@Override
		public void remove()
		{
			throw new UnsupportedOperationException();
		}

		private int[] makeNext()
		{
			if (next != null)
			{
				return next;
			}
			if (perm == null)
			{
				return null;
			}

			// find the largest element with != 0 direction
			int i = -1, e = -1;
			for (int j = 0; j < n; j++)
			{
				if ((dirs[j] != 0) && (perm[j] > e))
				{
					e = perm[j];
					i = j;
				}
			}

			if (i == -1)
			{
				return (next = (perm = (dirs = null))); // no more permutations
			}

			// swap with the element in its direction
			final int k = i + dirs[i];
			swap(i, k, dirs);
			swap(i, k, perm);
			// if it's at the start/end or the next element in the direction
			// is greater, reset its direction.
			if ((k == 0) || (k == n - 1) || (perm[k + dirs[k]] > e))
			{
				dirs[k] = 0;
			}

			// set directions to all greater elements
			for (int j = 0; j < n; j++)
			{
				if (perm[j] > e)
				{
					dirs[j] = (j < k) ? +1 : -1;
				}
			}

			return (next = perm);
		}

		protected void swap(final int i, final int j, final int[] arr)
		{
			final int v = arr[i];
			arr[i] = arr[j];
			arr[j] = v;
		}
	}
}
