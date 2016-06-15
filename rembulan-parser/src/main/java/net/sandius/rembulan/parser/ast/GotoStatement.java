package net.sandius.rembulan.parser.ast;

import net.sandius.rembulan.util.Check;

public class GotoStatement extends BodyStatement {

	private final Name labelName;

	public GotoStatement(SourceInfo src, Attributes attr, Name labelName) {
		super(src, attr);
		this.labelName = Check.notNull(labelName);
	}

	public GotoStatement(SourceInfo src, Name labelName) {
		this(src, Attributes.empty(), labelName);
	}

	public Name labelName() {
		return labelName;
	}

	public GotoStatement update(Name labelName) {
		if (this.labelName.equals(labelName)) {
			return this;
		}
		else {
			return new GotoStatement(sourceInfo(), attributes(), labelName);
		}
	}

	@Override
	public BodyStatement accept(Transformer tf) {
		return tf.transform(this);
	}

}