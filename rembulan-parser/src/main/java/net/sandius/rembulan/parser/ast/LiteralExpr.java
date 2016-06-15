package net.sandius.rembulan.parser.ast;

import net.sandius.rembulan.util.Check;

public class LiteralExpr extends Expr {

	private final Literal value;

	public LiteralExpr(SourceInfo src, Attributes attr, Literal value) {
		super(src, attr);
		this.value = Check.notNull(value);
	}

	public LiteralExpr(SourceInfo src, Literal value) {
		this(src, Attributes.empty(), value);
	}

	public Literal value() {
		return value;
	}

	@Override
	public Expr accept(Transformer tf) {
		return tf.transform(this);
	}

}