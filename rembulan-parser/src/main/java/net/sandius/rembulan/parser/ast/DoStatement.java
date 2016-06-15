package net.sandius.rembulan.parser.ast;

import net.sandius.rembulan.util.Check;

public class DoStatement extends BodyStatement {

	private final Block block;

	public DoStatement(SourceInfo src, Attributes attr, Block block) {
		super(src, attr);
		this.block = Check.notNull(block);
	}

	public DoStatement(SourceInfo src, Block block) {
		this(src, Attributes.empty(), block);
	}

	public Block block() {
		return block;
	}

	public DoStatement update(Block block) {
		if (this.block.equals(block)) {
			return this;
		}
		else {
			return new DoStatement(sourceInfo(), attributes(), block);
		}
	}

	@Override
	public BodyStatement accept(Transformer tf) {
		return tf.transform(this);
	}

}