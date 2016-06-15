package net.sandius.rembulan.parser;

import net.sandius.rembulan.parser.ast.BinaryOperationExpr;
import net.sandius.rembulan.parser.ast.Expr;
import net.sandius.rembulan.parser.ast.Operator;
import net.sandius.rembulan.parser.ast.SourceInfo;
import net.sandius.rembulan.parser.ast.UnaryOperationExpr;
import net.sandius.rembulan.util.Check;

import java.util.Stack;

class ExprBuilder {

	private final Stack<Expr> operandStack;
	private final Stack<SourceElement<Operator>> operatorStack;

	ExprBuilder() {
		this.operandStack = new Stack<>();
		this.operatorStack = new Stack<>();
	}

	private static boolean isRightAssociative(Operator op) {
		return op instanceof Operator.Binary && !((Operator.Binary) op).isLeftAssociative();
	}

	// true iff a takes precedence over b
	private static boolean hasLesserPrecedence(Operator newOp, Operator top) {
		Check.notNull(newOp);
		Check.notNull(top);

		return !(newOp instanceof Operator.Unary)
				&& (isRightAssociative(newOp)
						? newOp.precedence() < top.precedence()
						: newOp.precedence() <= top.precedence());
	}

	private void makeOp(SourceElement<Operator> srcOp) {
		SourceInfo src = srcOp.sourceInfo();
		Operator op = srcOp.element();

		if (op instanceof Operator.Binary) {
			Expr r = operandStack.pop();
			Expr l = operandStack.pop();
			operandStack.push(new BinaryOperationExpr(src, (Operator.Binary) op, l, r));
		}
		else if (op instanceof Operator.Unary) {
			Expr a = operandStack.pop();
			operandStack.push(new UnaryOperationExpr(src, (Operator.Unary) op, a));
		}
		else {
			throw new IllegalStateException("Illegal operator: " + op);
		}
	}

	public void addOp(SourceInfo src, Operator op) {
		Check.notNull(src);
		Check.notNull(op);

		while (!operatorStack.isEmpty() && hasLesserPrecedence(op, operatorStack.peek().element())) {
			makeOp(operatorStack.pop());
		}
		operatorStack.push(SourceElement.of(src, op));
	}

	public void addExpr(Expr expr) {
		Check.notNull(expr);
		operandStack.push(expr);
	}

	public Expr build() {
		while (!operatorStack.isEmpty()) {
			makeOp(operatorStack.pop());
		}

		Expr result = operandStack.pop();

		assert (operandStack.isEmpty());
		assert (operatorStack.isEmpty());

		return result;
	}

}