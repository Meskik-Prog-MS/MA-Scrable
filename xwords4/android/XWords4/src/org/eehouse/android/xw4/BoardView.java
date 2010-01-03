

package org.eehouse.android.xw4;

import android.view.View;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.content.Context;
import android.util.AttributeSet;
import org.eehouse.android.xw4.jni.*;
import android.view.MotionEvent;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;

public class BoardView extends View implements DrawCtx, 
                                               BoardHandler {

    private Paint m_fillPaint;
    private Paint m_strokePaint;
    private int m_jniGamePtr;
    private CurGameInfo m_gi;
    private boolean m_boardSet = false;
    private Canvas m_canvas;
    private int m_trayOwner;
    private Rect m_valRect;
    private Rect m_letterRect;
    Drawable m_rightArrow;
    Drawable m_downArrow;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TILE_BACK = 0xFFFFFF99;
    private int [] m_bonusColors = { WHITE,         // BONUS_NONE
                                     0xFFAFAF00,	  /* bonus 1 */
                                     0xFF00AFAF,
                                     0xFFAF00AF,
                                     0xFFAFAFAF };
    private static final int[] m_playerColors = {
        0xFF000000,
        0xFFFF0000,
        0xFF0000FF,
        0xFF008F00,
    };



    public BoardView( Context context ) {
        super( context );
        init();
    }

    // called when inflating xml
    public BoardView( Context context, AttributeSet attrs ) {
        super( context, attrs );
        init();
    }

    // public boolean onClick( View view ) {
    //     Utils.logf( "onClick called" );
    //     return view == this;
    // }

    public boolean onTouchEvent( MotionEvent event ) {
        int action = event.getAction();
        int xx = (int)event.getX();
        int yy = (int)event.getY();
        boolean draw = false;
        
        switch ( action ) {
        case MotionEvent.ACTION_DOWN:
            boolean[] handled = new boolean[1];
            draw = XwJNI.board_handlePenDown( m_jniGamePtr, xx, yy, handled );
            break;
        case MotionEvent.ACTION_MOVE:
            draw = XwJNI.board_handlePenMove( m_jniGamePtr, xx, yy );
            break;
        case MotionEvent.ACTION_UP:
            draw = XwJNI.board_handlePenUp( m_jniGamePtr, xx, yy );
            break;
        default:
            Utils.logf( "unknown action: " + action );
            Utils.logf( event.toString() );
        }
        if ( draw ) {
            invalidate();
        }
        return true;             // required to get subsequent events
    }

    protected void onDraw( Canvas canvas ) {
        if ( layoutBoardOnce() ) {
            m_canvas = canvas;       // save for callbacks
            XwJNI.board_invalAll( m_jniGamePtr ); // get rid of this!
            if ( XwJNI.board_draw( m_jniGamePtr ) ) {
                Utils.logf( "draw succeeded" );
            }
            m_canvas = null;       // clear!
        }
    }

    private void init() {
        setFocusable(true);
        setBackgroundDrawable( null );

        m_fillPaint = new Paint();
        m_fillPaint.setTextAlign( Paint.Align.CENTER ); // center horizontally
        m_strokePaint = new Paint();
        m_strokePaint.setStyle( Paint.Style.STROKE );

        // Move this to finalize?
        // XwJNI.game_dispose( jniGamePtr );
        // Utils.logf( "game_dispose returned" );
        // jniGamePtr = 0;
    }

    private boolean layoutBoardOnce() {
        if ( !m_boardSet && null != m_gi ) {
            m_boardSet = true;

            // For now we're assuming vertical orientation.  Fix way
            // later.

            int width = getWidth();
            int height = getHeight();
            int nCells = m_gi.boardSize;
            int cellSize = width / nCells;
            int border = (width % nCells) / 2;
            int scoreHt = cellSize; // scoreboard ht same as cells for
                                    // proportion

            XwJNI.board_setPos( m_jniGamePtr, border, border+cellSize, false );
            XwJNI.board_setScale( m_jniGamePtr, cellSize, cellSize );
            XwJNI.board_setScoreboardLoc( m_jniGamePtr, border, border,
                                               nCells * cellSize, // width
                                               scoreHt, true );

            XwJNI.board_setTrayLoc( m_jniGamePtr, border,
                                         border + scoreHt + (nCells * cellSize),
                                         nCells * cellSize, // width
                                         cellSize * 2,      // height
                                         4 );
            XwJNI.board_setShowColors( m_jniGamePtr, true );
            XwJNI.board_invalAll( m_jniGamePtr );
        }
        return m_boardSet;
    }

    public void startHandling( Context context, int gamePtr, CurGameInfo gi ) {
        m_jniGamePtr = gamePtr;
        m_gi = gi;

        Resources res = context.getResources();
        m_downArrow = res.getDrawable( R.drawable.downarrow );
        m_rightArrow = res.getDrawable( R.drawable.rightarrow );
    }

    // DrawCtxt interface implementation
    public void measureRemText( Rect r, int nTilesLeft, int[] width, int[] height ) {
        width[0] = 30;
        height[0] = r.bottom - r.top;
    }

    public void measureScoreText( Rect r, DrawScoreInfo dsi, int[] width, int[] height )
    {
        width[0] = 60;
        height[0] = r.bottom - r.top;
    }

    public void drawRemText( Rect rInner, Rect rOuter, int nTilesLeft, boolean focussed )
    {
        String text = String.format( "%d", nTilesLeft ); // should cache a formatter
        m_fillPaint.setColor( TILE_BACK );
        m_canvas.drawRect( rOuter, m_fillPaint );

        m_fillPaint.setTextSize( rInner.bottom - rInner.top );
        m_fillPaint.setColor( BLACK );
        drawCentered( text, rInner );
    }

    public void score_drawPlayer( Rect rInner, Rect rOuter, DrawScoreInfo dsi )
    {
        String text = String.format( "%s:%d", dsi.name, dsi.totalScore );
        m_fillPaint.setTextSize( rInner.bottom - rInner.top );
        m_fillPaint.setColor( m_playerColors[dsi.playerNum] );
        drawCentered( text, rInner );
    }

    public boolean drawCell( Rect rect, String text, Object[] bitmaps, int tile, 
                             int owner, int bonus, int hintAtts, int flags ) 
    {
        int backColor;
        int foreColor = WHITE;  // must be initialized :-(
        boolean empty = null == text && null == bitmaps;
        boolean pending = 0 != (flags & CELL_HIGHLIGHT);
        if ( empty ) {
            backColor = m_bonusColors[bonus];
        } else if ( pending ) {
            backColor = BLACK;
        } else {
            backColor = TILE_BACK; 
            foreColor = m_playerColors[owner];
        }

        m_fillPaint.setColor( backColor );
        m_canvas.drawRect( rect, m_fillPaint );

        if ( !empty ) {
            m_fillPaint.setTextSize( rect.bottom - rect.top );
            if ( owner < 0 ) {  // showColors option not turned on
                owner = 0;
            }
            m_fillPaint.setColor( foreColor );
            drawCentered( text, rect );
        }

        m_canvas.drawRect( rect, m_strokePaint );
        return true;
    }

    public void drawBoardArrow ( Rect rect, int bonus, boolean vert, int hintAtts,
                                 int flags )
    {
        rect.inset( 2, 2 );
        Utils.logf( "drawBoardArrow" );
        Drawable arrow = vert? m_downArrow : m_rightArrow;
        arrow.setBounds( rect );
        arrow.draw( m_canvas );
    }

    public boolean trayBegin ( Rect rect, int owner, int dfs ) {
        m_trayOwner = owner;
        return true;
    }

    public void drawTile( Rect rect, String text, Object[] bitmaps, int val, 
                          int flags ) {
        boolean valHidden = (flags & CELL_VALHIDDEN) != 0;
        boolean notEmpty = (flags & CELL_ISEMPTY) == 0;
        boolean isCursor = (flags & CELL_ISCURSOR) != 0;

        if ( isCursor || notEmpty ) {
            m_fillPaint.setColor( TILE_BACK );
            m_canvas.drawRect( rect, m_fillPaint );

            if ( null != text ) {
                m_fillPaint.setColor( m_playerColors[m_trayOwner] );
                positionDrawTile( rect, text, val );
            }

            m_canvas.drawRect( rect, m_strokePaint ); // frame
        }
    }

    public void drawTileMidDrag ( Rect rect, String text, Object[] bitmaps,
                           int val, int owner, int flags ) 
    {
        drawTile( rect, text, bitmaps, val, flags );
    }

    public void drawTileBack( Rect rect, int flags ) 
    {
        drawTile( rect, "?", null, 0, flags );
    }

    public void drawTrayDivider( Rect rect, int flags ) 
    {
        m_fillPaint.setColor( BLACK ); // black for now
        m_canvas.drawRect( rect, m_fillPaint );
    }

    public void score_pendingScore( Rect rect, int score, int playerNum, 
                                    int flags ) 
    {
        String text = score >= 0? String.format( "%d", score ) : "??";
        m_fillPaint.setColor( BLACK );
        m_fillPaint.setTextSize( (rect.bottom - rect.top) / 2 );
        drawCentered( text, rect );
    }


    private void drawCentered( String text, Rect rect ) {
        int bottom = rect.bottom;
        int center = rect.left + ( (rect.right - rect.left) / 2 );
        m_canvas.drawText( text, center, bottom, m_fillPaint );
    }

    private void positionDrawTile( Rect rect, String text, int val )
    {
        if ( null == m_letterRect ) {
            // assumes show values is on
            m_letterRect = new Rect( 0, 0, rect.width() * 3 / 4, rect.height() * 3 / 4 );
        }
        m_letterRect.offsetTo( rect.left, rect.top );
        m_fillPaint.setTextSize( m_letterRect.height() );
        drawCentered( text, m_letterRect );

        if ( null == m_valRect ) {
            m_valRect = new Rect( 0, 0, rect.width() / 4, rect.height() / 4 );
        }
        m_valRect.offsetTo( rect.right - (rect.width() / 4),
                            rect.bottom - (rect.height() / 4) );
        text = String.format( "%d", val );
        m_fillPaint.setTextSize( m_valRect.height() );
        drawCentered( text, m_valRect );
    }
    
}