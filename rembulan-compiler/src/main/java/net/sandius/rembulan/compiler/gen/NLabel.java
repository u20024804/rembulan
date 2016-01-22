package net.sandius.rembulan.compiler.gen;

import net.sandius.rembulan.util.Check;

import java.util.Collections;

public class NLabel extends NNode {

	public final String name;
	private NNode next;

	public NLabel(String name, NNode n) {
		super();
		this.name = name;
		this.next = n;
	}

	public NLabel(String name) {
		this(name, null);
	}

	public NLabel() {
		this(null);
	}

	@Override
	public String selfToString() {
		return "@" + (name != null ? name : Integer.toHexString(System.identityHashCode(this)));
	}

	@Override
	public String nextToString() {
		return next != null ? next.toString() : "NULL";
	}

	@Override
	public final Iterable<NNode> out() {
		if (next != null) {
			return Collections.singleton(next);
		}
		else {
			return Collections.emptySet();
		}
	}

	@Override
	public final int outDegree() {
		return next != null ? 1 : 0;
	}

	@Override
	public void replaceOutgoing(NNode n, NNode replacement) {
		Check.notNull(n);
		Check.isEq(n, next);

		next = replacement;
	}

	public NLabel followedBy(NNode n) {
		Check.notNull(n);

		if (next != null) {
			// detach from old next
			next.detachIncoming(this);
		}

		next = n;
		n.attachIncoming(this);

		return this;
	}

	public void remove() {
		Check.isEq(inDegree(), 1);
		Check.notNull(next);

		NNode nxt = next;
		next.detachIncoming(this);
		next = null;

		for (NNode n : in()) {
			n.replaceOutgoing(this, nxt);
			nxt.attachIncoming(n);
		}
	}

	public void insertBefore(NNode n) {
		Check.notNull(n);

		if (next != null) {
			next.detachIncoming(this);
		}

		for (NNode m : n.in()) {
			m.replaceOutgoing(n, this);
			n.detachIncoming(m);
		}

		next = n;
		n.attachIncoming(this);
	}

	public static NLabel guard(NNode n) {
		if (n instanceof NLabel) {
			return (NLabel) n;
		}
		else {
			// insert a label node in front of n
			NLabel l = new NLabel();
			l.insertBefore(n);
			return l;
		}
	}

}
