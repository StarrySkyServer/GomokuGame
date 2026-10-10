package top.tabletopgame.xiangqi.pengjiu.chessmove;

import static top.tabletopgame.xiangqi.pengjiu.ChessConstant.*;


import java.util.ArrayList;
import java.util.List;

import top.tabletopgame.xiangqi.pengjiu.BitBoard;
import top.tabletopgame.xiangqi.pengjiu.ChessConstant;
import top.tabletopgame.xiangqi.pengjiu.Tools;
import top.tabletopgame.xiangqi.pengjiu.chessparam.ChessParam;
import top.tabletopgame.xiangqi.pengjiu.evaluate.EvaluateCompute;
import top.tabletopgame.xiangqi.pengjiu.evaluate.EvaluateComputeMiddle;
import top.tabletopgame.xiangqi.pengjiu.history.CHistoryHeuritic;
import top.tabletopgame.xiangqi.pengjiu.zobrist.TranspositionTable;

public class ChessQuiescMove extends ChessMoveAbs{

	
//	public static final int BLACKKING=KING+7;    //王
//	public static final int BLACKCHARIOT=CHARIOT+7; //车
//	public static final int BLACKKNIGHT=KNIGHT+7; //马
//	public static final int BLACKGUN=GUN+7; //炮
//	public static final int BLACKELEPHANT=ELEPHANT+7; //象
//	public static final int BLACKGUARD=GUARD+7; //士
//	public static final int BLACKSOLDIER=SOLDIER+7; //兵
	
	
	
	public ChessQuiescMove(ChessParam chessParam, TranspositionTable tranTable,EvaluateCompute evaluateCompute) {
		super(chessParam, tranTable,evaluateCompute); 
	}
	
	
	
	
	
	/**记录下所有可走的方式
	 * @param srcSite
	 * @param destSite
	 * @param play
	 */
	public void savePlayChess(int srcSite,int destSite,int play){
		int destChess=board[destSite];
		int srcChess=board[srcSite];
		MoveNode moveNode = null;
		if (destChess != NOTHING ){
			int destScore=0;
			int srcScore=0;
			destScore=EvaluateCompute.chessBaseScore[destChess]+evaluateCompute.chessAttachScore(chessRoles[destChess], destSite);
			if (destScore>=150) {  //吃子
				//要吃的柜子被对手保护
				srcScore=EvaluateCompute.chessBaseScore[srcChess]+evaluateCompute.chessAttachScore(chessRoles[srcChess], srcSite);	
				//按被吃棋子价值排序
				moveNode = new MoveNode(srcSite,destSite,srcChess,destChess,destScore-srcScore);
				goodMoveList.add(moveNode);
				return ;
			}
		}
		//历吏表排序
		moveNode=new MoveNode(srcSite,destSite,srcChess,destChess,CHistoryHeuritic.cHistory[ChessConstant.chessRoles_eight[srcChess]][destSite]);
		generalMoveList.add(moveNode); //不吃子
	}
}
















