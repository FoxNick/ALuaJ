/*
 * Copyright (c) 2013 Tah Wei Hoon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License Version 2.0,
 * with full text available at http://www.apache.org/licenses/LICENSE-2.0.html
 *
 * This software is provided "as is". Use at your own risk.
 */
package com.myopicmobileX.textwarrior.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.mythoi.androluaj.util.APIConfig;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.HashMap;
import android.graphics.*;

public class Lexer
{

	//mythoi
	public static HashMap<String,String> varAndClass;


	private final static int MAX_KEYWORD_LENGTH = 127;
	public final static int UNKNOWN = -1;
	public final static int NORMAL = 0;
	public final static int KEYWORD = 1;
	public final static int OPERATOR = 2;
	public final static int NAME = 3;
	public final static int LITERAL = 4;
	/** A word that starts with a special symbol, inclusive.
	 * Examples:
	 * :ruby_symbol
	 * */
	public final static int SINGLE_SYMBOL_WORD = 10;

	/** Tokens that extend from a single start symbol, inclusive, until the end of line.
	 * Up to 2 types of symbols are supported per language, denoted by A and B
	 * Examples:
	 * #include "myCppFile"
	 * #this is a comment in Python
	 * %this is a comment in Prolog
	 * */
	public final static int SINGLE_SYMBOL_LINE_A = 20;
	public final static int SINGLE_SYMBOL_LINE_B = 21;

	/** Tokens that extend from a two start symbols, inclusive, until the end of line.
	 * Examples:
	 * //this is a comment in C
	 * */
	public final static int DOUBLE_SYMBOL_LINE = 30;

	/** Tokens that are enclosed between a start and end sequence, inclusive,
	 * that can span multiple lines. The start and end sequences contain exactly
	 * 2 symbols.
	 * Examples:
	 * {- this is a...
	 *  ...multi-line comment in Haskell -}
	 * */
	public final static int DOUBLE_SYMBOL_DELIMITED_MULTILINE = 40;

	/** Tokens that are enclosed by the same single symbol, inclusive, and
	 * do not span over more than one line.
	 * Examples: 'c', "hello world"
	 * */
	public final static int SINGLE_SYMBOL_DELIMITED_A = 50;
	public final static int SINGLE_SYMBOL_DELIMITED_B = 51;

	private static Language _globalLanguage = LanguageNonProg.getInstance();
	synchronized public static void setLanguage(Language lang)
	{
		_globalLanguage = lang;
	}

	synchronized public static Language getLanguage()
	{
		return _globalLanguage;
	}


	private DocumentProvider _hDoc;
	private LexThread _workerThread = null;
	LexCallback _callback = null;

	public Lexer(LexCallback callback)
	{
		_callback = callback;
	}

	public void tokenize(DocumentProvider hDoc)
	{
		if (!Lexer.getLanguage().isProgLang())
		{
			return;
		}

		//tokenize will modify the state of hDoc; make a copy
		setDocument(new DocumentProvider(hDoc));
		if (_workerThread == null)
		{
			_workerThread = new LexThread(this);
			_workerThread.start();
		}
		else
		{
			_workerThread.restart();
		}
	}

	void tokenizeDone(List<Pair> result)
	{
		if (_callback != null)
		{
			_callback.lexDone(result, apiClasses);
		}
		_workerThread = null;
	}

	public void cancelTokenize()
	{
		if (_workerThread != null)
		{
			_workerThread.abort();
			_workerThread = null;
		}
	}

	public synchronized void setDocument(DocumentProvider hDoc)
	{
		_hDoc = hDoc;
	}

	public synchronized DocumentProvider getDocument()
	{
		return _hDoc;
	}


	private static ArrayList<Rect> mLines = new ArrayList<>();

    public static ArrayList<Rect> getLines()
	{
        return mLines;
    }



	private ArrayList<String> apiClasses;
	private class LexThread extends Thread
	{
		private boolean rescan = false;
		private final Lexer _lexManager;
		/** can be set by another thread to stop the scan immediately */
		private final Flag _abort;
		/** A collection of Pairs, where Pair.first is the start
		 *  position of the token, and Pair.second is the type of the token.*/
		private ArrayList<Pair> _tokens;

		public LexThread(Lexer p)
		{
			apiClasses = new ArrayList<String>();
			_lexManager = p;
			_abort = new Flag();
		}

		@Override
		public void run()
		{
			do{
				rescan = false;
				_abort.clear();
				tokenize();
			}
			while(rescan);

			if (!_abort.isSet())
			{
				// lex complete
				_lexManager.tokenizeDone(_tokens);
			}
		}

		public void restart()
		{
			rescan = true;
			_abort.set();
		}

		public void abort()
		{
			_abort.set();
		}

        //mythoi
		String lastName=" ";
		ArrayList<String> names;
		int index=0;
		private void tokenize()
		{
        	lastName = " ";
			index = 0;

			if (names == null)
				names = new ArrayList<String>();
			else
				names.clear();

			if (varAndClass == null)
				varAndClass = new HashMap<String,String>();
			else
				varAndClass.clear();
			DocumentProvider hDoc = getDocument();

			int rowCount = hDoc.getRowCount();
            int maxRow = 9999;

			ArrayList<Pair> tokens = new ArrayList<Pair>(8196);

			ArrayList<Rect> lines = new ArrayList<>(8196);
            ArrayList<Rect> lineStacks = new ArrayList<>(8196);
            ArrayList<Rect> lineStacks2 = new ArrayList<>(8196);


			LuaLexer lexer = new LuaLexer(hDoc);
			Language language = Lexer.getLanguage();
			language.clearUserWord();
			
			language.clearNormalWord();

			/*mythoi 改- 变量联想  有点卡*/
//			Pattern pattern = Pattern.compile("("+ApiListView.clazz+")\\s(\\s)*(\\w)*");
//			Matcher matcher =pattern.matcher(MainActivity.edit.getText().toString());
//			while (matcher.find())
//			{
//				try{
//					String str1=matcher.group();
//					String strClaz[]=str1.split(" ");
//					System.out.println(strClaz[1]);
//					if(!language.isUserWord(strClaz[1].replaceAll(" ","")))
//						language.addUserWord(strClaz[1].replaceAll(" ",""));
//				}catch(Exception e){}
//			}
//						//-----------

			try
			{
				int idx = 0;

				LuaTokenTypes lastType = null;
				LuaTokenTypes lastType2 = null;


				Pair lastPair = null;
				StringBuilder bul=new StringBuilder();			
				boolean isModule=false;
				boolean hasDo = true;
				while (!_abort.isSet())
				{
					Pair pair = null;
					LuaTokenTypes type = lexer.advance();
					if (type == null)
						break;
					int len = lexer.yylength();
					String name1=lexer. yytext();

					//mythoi改，支持变量高亮，lua补全-----
			     	names.add(name1);
					String claz0=APIConfig.RevoltHashMap.get(name1);//claz为全类名
					if (names.size() > 2)
					{
						if (claz0 != null && !language.isUserWord(names.get(index - 2)) && "=".equals(names.get(index - 1)))
						{
							language.addNormalWord(names.get(index - 2));					
							varAndClass.put(names.get(index - 2), claz0);				
						}
						else if (!language.isUserWord(names.get(index - 2)) && "=".equals(names.get(index - 1)))
						{				
							language.addNormalWord(names.get(index - 2));					
						}					
					}
					index++;

					//mythoi改，支持变量高亮，java补全-----
					String claz=APIConfig.RevoltHashMap.get(lastName);//claz为全类名

					if (lastName.equals("long") || lastName.equals("int") || lastName.equals("short") || lastName.equals("byte") || lastName.equals("float") || lastName.equals("double") || lastName.equals("char") || lastName.equals("boolean"))
					{
						//添加这个是为了可以使int、long....这些修饰的变量高亮和补全
						claz = " ";
					}

					if (claz != null && !language.isUserWord(name1))
					{
						language.addNormalWord(name1);//addUserWord
						varAndClass.put(name1, claz);
					}



					//mythoi改，支持// /*注释
//					if (lastName.equals(name1)&&name1.equals("/"))		
//						lastPair.setFirst(lastPair.getFirst() + len);
//					else if(lastName.equals("/")&&name1.equals("*"))
//						tokens.add(pair = new Pair(len, DOUBLE_SYMBOL_LINE));

					if (!name1.contains(" "))
						lastName = name1;			
					//-----------


					if (isModule && lastType == LuaTokenTypes.STRING && type != LuaTokenTypes.STRING)
					{
						String mod=bul.toString();
						if (bul.length() > 2)
							language.addUserWord(mod.substring(1, mod.length() - 1));
						bul = new StringBuilder();
						isModule = false;
					}


					if (lastType2 == type && lastPair != null)
					{
						lastPair.setFirst(lastPair.getFirst() + len);
					}				
					else if (language.isKeyword(name1))
					{
						//关键 if ↑↑↑↑↑↑↑关键字 mythoi改，支持添加关键字
						//System.out.println("关键字"+len);
						tokens.add(new Pair(len, KEYWORD));
					}
					else if (type == LuaTokenTypes.LPAREN || type == LuaTokenTypes.RPAREN
							 || type == LuaTokenTypes.LBRACK || type == LuaTokenTypes.RBRACK
							 || type == LuaTokenTypes.LCURLY || type == LuaTokenTypes.RCURLY
							 || type == LuaTokenTypes.COMMA || type == LuaTokenTypes.DOT)
					{
						//括号
						tokens.add(pair = new Pair(len, OPERATOR));
					}
					else if (type == LuaTokenTypes.STRING || type == LuaTokenTypes.LONG_STRING)
					{
						//字符串
						if (lastType != type)
						{
							tokens.add(pair = new Pair(len, SINGLE_SYMBOL_DELIMITED_A));
							if (lastName.equals("require"))
								isModule = true;
						}
						else
						{
							lastPair.setFirst(lastPair.getFirst() + len);
						}
						if (isModule)
							bul.append(lexer.yytext());
					}
					else if (type == LuaTokenTypes.NAME)
					{		
						/*
						 if (rowCount > maxRow) {
						 tokens.add(new Pair(len, NORMAL));
						 break;
						 }
						 */
						String name=lexer.yytext();

						if (lastType == LuaTokenTypes.FUNCTION)
						{
							//函数名
							tokens.add(new Pair(len, LITERAL));
							language.addUserWord(name);
						}
						else if (language.isUserWord(name))
						{
							tokens.add(new Pair(len, LITERAL));
						}
						else if (language.isBasePackage(name))
						{
							tokens.add(new Pair(len, NAME));
						}
						else if (lastType == LuaTokenTypes.DOT && language.isBasePackage(lastName) && language.isBaseWord(lastName, name))
						{
							//标准库函数
							tokens.add(new Pair(len, NAME));
						}
						//else if (lastType == LuaTokenTypes.DOT || lastType == LuaTokenTypes.COLON)
						//{
						//其他
						//tokens.add(new Pair(len, LITERAL));
						//}
						else if (language.isName(name))
						{
							apiClasses.add(name);
							tokens.add(new Pair(len, NAME));
						}
						else
						{
							tokens.add(new Pair(len, NORMAL));
						}
						if (lastType == LuaTokenTypes.ASSIGN && "require".equals(name))
						{
							language.addUserWord(lastName);
							if (tokens.size() >= 3)
							{
								Pair p=tokens.get(tokens.size() - 3);
								p.setSecond(NAME);
							}
						}

					}
					else if (type == LuaTokenTypes.SHORT_COMMENT || type == LuaTokenTypes.BLOCK_COMMENT || type == LuaTokenTypes.DOC_COMMENT)
					{			
						//注释--和[[]]
						if (lastType != type)
							tokens.add(pair = new Pair(len, DOUBLE_SYMBOL_LINE));
						else
							lastPair.setFirst(lastPair.getFirst() + len);

					}
					else if (type == LuaTokenTypes.NUMBER)
					{
						tokens.add(new Pair(len, LITERAL));
					}		
					else
					{			

						tokens.add(pair = new Pair(len, NORMAL));
					}

					if (type != LuaTokenTypes.WHITE_SPACE)
					//&& type != LuaTokenTypes.NEWLINE
					//	&& type != LuaTokenTypes.NL_BEFORE_LONGSTRING)
					{
						lastType = type;
					}
					lastType2 = type;
					if (pair != null)
						lastPair = pair;
					idx += len;


					//代码块对齐线========
					switch (type)
					{

						case DO:
                            if (hasDo)
							{
                                lineStacks.add(new Rect(lexer.yychar(), lexer.yyline() + 1, 0, lexer.yyline()));
                            }
                            hasDo = true;
							break;
						case WHILE:
                        case FOR:
                            hasDo = false;
                            lineStacks.add(new Rect(lexer.yychar(), lexer.yyline() + 1, 0, lexer.yyline()));
							break;
						case FUNCTION:
                        case IF:
                        case SWITCH:
                            lineStacks.add(new Rect(lexer.yychar(), lexer.yyline() + 1, 0, lexer.yyline()));
							break;
						case END:
                            int size = lineStacks.size();
                            if (size > 0)
							{
                                Rect rect = lineStacks.remove(size - 1);
                                rect.bottom = lexer.yyline();
                                rect.right = lexer.yychar();
                                if (rect.bottom != rect.top)
                                    lines.add(rect);
                            }
                            hasDo = true;
                            break;
						case LCURLY:
                            lineStacks2.add(new Rect(lexer.yychar(), lexer.yyline() + 1, 0, lexer.yyline()));
                            break;
                        case RCURLY:
                            int size2 = lineStacks2.size();
                            if (size2 > 0)
							{
                                Rect rect = lineStacks2.remove(size2 - 1);
                                rect.bottom = lexer.yyline();
                                rect.right = lexer.yychar();
                                if (rect.bottom != rect.top)
									lines.add(rect);
                            }
							break;
					}
					//======================


				}


			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			if (tokens.isEmpty())
			{
				// return value cannot be empty
				tokens.add(new Pair(0, NORMAL));
			}

			language.updateUserWord();
			
			language.updateNormalWord();
			
			
			mLines = lines;
			_tokens = tokens;
		}


		/**
		 * Scans the document referenced by _lexManager for tokens.
		 * The result is stored internally.
		 */
		public void tokenize2()
		{
			DocumentProvider hDoc = getDocument();
			Language language = Lexer.getLanguage();
			ArrayList<Pair> tokens = new ArrayList<Pair>();

			if (!language.isProgLang())
			{
				tokens.add(new Pair(0, NORMAL));
				_tokens = tokens;
				return;
			}

			char[] candidateWord = new char[MAX_KEYWORD_LENGTH];
			int currentCharInWord = 0;
			int currentCharStartWord = 0;

			int spanStartPosition = 0;
			int workingPosition = 0;
			int state = UNKNOWN;
			char prevChar = 0;

			hDoc.seekChar(0);
			while (hDoc.hasNext() && !_abort.isSet())
			{
				char currentChar = hDoc.next();

				switch (state)
				{
					case UNKNOWN: //fall-through
					case NORMAL: //fall-through
					case KEYWORD: //fall-through
					case NAME: //fall-through
					case SINGLE_SYMBOL_WORD:
						int pendingState = state;
						boolean stateChanged = false;
						if (language.isLineStart(prevChar, currentChar))
						{
							pendingState = DOUBLE_SYMBOL_LINE;
							stateChanged = true;
						}
						else if (language.isMultilineStartDelimiter(prevChar, currentChar))
						{
							pendingState = DOUBLE_SYMBOL_DELIMITED_MULTILINE;
							stateChanged = true;
						}
						else if (language.isDelimiterA(currentChar))
						{
							pendingState = SINGLE_SYMBOL_DELIMITED_A;
							stateChanged = true;
						}
						else if (language.isDelimiterB(currentChar))
						{
							pendingState = SINGLE_SYMBOL_DELIMITED_B;
							stateChanged = true;
						}
						else if (language.isLineAStart(currentChar))
						{
							pendingState = SINGLE_SYMBOL_LINE_A;
							stateChanged = true;
						}
						else if (language.isLineBStart(currentChar))
						{
							pendingState = SINGLE_SYMBOL_LINE_B;
							stateChanged = true;
						}


						if (stateChanged)
						{
							if (pendingState == DOUBLE_SYMBOL_LINE ||
								pendingState == DOUBLE_SYMBOL_DELIMITED_MULTILINE)
							{
								// account for previous char
								spanStartPosition = workingPosition - 1;
//TODO consider less greedy approach and avoid adding token for previous char
								if (tokens.get(tokens.size() - 1).getFirst() == spanStartPosition)
								{
									tokens.remove(tokens.size() - 1);
								}
							}
							else
							{
								spanStartPosition = workingPosition;
							}

							// If a span appears mid-word, mark the chars preceding
							// it as NORMAL, if the previous span isn't already NORMAL
							if (currentCharInWord > 0 && state != NORMAL)
							{
								tokens.add(new Pair(workingPosition - currentCharInWord, NORMAL));
							}

							state = pendingState;
							tokens.add(new Pair(spanStartPosition, state));
							currentCharInWord = 0;
						}

						else if (language.isWhitespace(currentChar) || language.isOperator(currentChar))
						{
							if (currentCharInWord > 0)
							{
								// full word obtained; mark the beginning of the word accordingly
								if (language.isWordStart(candidateWord[0]))
								{
									spanStartPosition = workingPosition - currentCharInWord;
									state = SINGLE_SYMBOL_WORD;
									tokens.add(new Pair(spanStartPosition, state));
								}
								else if (language.isKeyword(new String(candidateWord, 0, currentCharInWord)))
								{
									spanStartPosition = workingPosition - currentCharInWord;
									state = KEYWORD;
									tokens.add(new Pair(spanStartPosition, state));
								}
								else if (language.isName(new String(candidateWord, 0, currentCharInWord)))
								{
									spanStartPosition = workingPosition - currentCharInWord;
									state = NAME;
									tokens.add(new Pair(spanStartPosition, state));
								}
								else if (state != NORMAL)
								{
									spanStartPosition = workingPosition - currentCharInWord;
									state = NORMAL;
									tokens.add(new Pair(spanStartPosition, state));
								}
								currentCharInWord = 0;
							}

							// mark operators as normal
							if (state != NORMAL && language.isOperator(currentChar))
							{
								state = NORMAL;
								tokens.add(new Pair(workingPosition, state));
							}
						}
						else if (currentCharInWord < MAX_KEYWORD_LENGTH)
						{
							// collect non-whitespace chars up to MAX_KEYWORD_LENGTH
							candidateWord[currentCharInWord] = currentChar;
							currentCharInWord++;
						}
						break;


					case DOUBLE_SYMBOL_LINE: // fall-through
					case SINGLE_SYMBOL_LINE_A: // fall-through
					case SINGLE_SYMBOL_LINE_B:
						if (language.isMultilineStartDelimiter(prevChar, currentChar))
						{
							state = DOUBLE_SYMBOL_DELIMITED_MULTILINE;
						}
						else if (currentChar == '\n')
						{
							state = UNKNOWN;
						}
						break;


					case SINGLE_SYMBOL_DELIMITED_A:
						if ((language.isDelimiterA(currentChar) || currentChar == '\n')
							&& !language.isEscapeChar(prevChar))
						{
							state = UNKNOWN;
						}
						// consume escape of the escape character by assigning
						// currentChar as something else so that it would not be
						// treated as an escape char in the next iteration
						else if (language.isEscapeChar(currentChar) && language.isEscapeChar(prevChar))
						{
							currentChar = ' ';
						}
						break;


					case SINGLE_SYMBOL_DELIMITED_B:
						if ((language.isDelimiterB(currentChar) || currentChar == '\n')
							&& !language.isEscapeChar(prevChar))
						{
							state = UNKNOWN;
						}
						// consume escape of the escape character by assigning
						// currentChar as something else so that it would not be
						// treated as an escape char in the next iteration
						else if (language.isEscapeChar(currentChar)
								 && language.isEscapeChar(prevChar))
						{
							currentChar = ' ';
						}
						break;

					case DOUBLE_SYMBOL_DELIMITED_MULTILINE:
						if (language.isMultilineEndDelimiter(prevChar, currentChar))
						{
							state = UNKNOWN;
						}
						break;

					default:
						TextWarriorException.fail("Invalid state in TokenScanner");
						break;
				}
				++workingPosition;
				prevChar = currentChar;
			}
			// end state machine


			if (tokens.isEmpty())
			{
				// return value cannot be empty
				tokens.add(new Pair(0, NORMAL));
			}

			_tokens = tokens;
		}


	}//end inner class

	private static boolean isKeyword(LuaTokenTypes t)
	{
        switch (t)
		{
			case TRUE:
			case FALSE:
            case DO:
            case FUNCTION:
            case NOT:
            case AND:
            case OR:
            case WITH:
            case IF:
            case THEN:
            case ELSEIF:
            case ELSE:
            case WHILE:
            case FOR:
            case IN:
            case RETURN:
            case BREAK:
            case CONTINUE:
            case LOCAL:
            case REPEAT:
            case UNTIL:
            case END:
            case NIL:
			case INT:
                return true;
            default:
                return false;
        }
    }

	public interface LexCallback
	{
		public void lexDone(List<Pair> results, ArrayList<String> apiClasses);
	}
}
