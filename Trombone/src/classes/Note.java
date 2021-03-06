package classes;

public class Note {
	private int id;
	private int page;
	private int order;
	private int pitch; // temporary set 0 as G
	private int beat; // no saenggak yet
	private boolean isRest;
	private boolean isAccidental;
	public int x;
	public int y;
	private int musicsheet_id;

	// TODO : sharp? flat?

	public Note() {
		super();
	}

	public Note(int pitch, int beat, int rest,  int accidental) {
		this.pitch = pitch;
		this.beat = beat;
		this.isRest = (rest == 1) ? true : false;
		this.isAccidental = (accidental == 1) ? true : false;
		this.x = -100;
		this.y = -100;
	}

	public Note(int pitch, int beat) {
		this.pitch = pitch;
		this.beat = beat;
		this.isRest = false;
		this.isAccidental = false;
		this.x = -100;
		this.y = -100;
	}
	
	public Note(int pitch) {
		this.pitch = pitch;
		this.beat = 1;
		this.isRest = false;
		this.isAccidental = false;
		this.x = -100;
		this.y = -100;
	}

	public Note(boolean isAccidental, int pitch, int beat) {
		this.pitch = pitch;
		this.beat = beat;
		this.isRest = false;
		this.isAccidental = isAccidental;
		this.x = -100;
		this.y = -100;
	}
	
	public Note(boolean isAccidental, int pitch) {
		this.pitch = pitch;
		this.beat = 1;
		this.isRest = false;
		this.isAccidental = isAccidental;
		this.x = -100;
		this.y = -100;
	}


	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getPage() {
		return page;
	}

	public void setPage(int page) {
		this.page = page;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public int getX() {
		return x;
	}

	public void setX(int x) {
		this.x = x;
	}

	public int getY() {
		return y;
	}

	public void setY(int y) {
		this.y = y;
	}

	public int getPitch() {
		return pitch;
	}

	public void setPitch(int pitch) {
		this.pitch = pitch;
	}

	public int getBeat() {
		return beat;
	}

	public void setBeat(int beat) {
		this.beat = beat;
	}

	public int getIsRest() {
		if ( isRest )
			return 1;
		
		return 0;
	}

	public void setIsRest(int a) {
		if ( a == 1 )
			this.isRest = true;
		else
			this.isRest = false;
	}
	
	public int getIsAccidental() {
		if ( isAccidental )
			return 1;
		
		return 0;
	}

	public void setIsAccidental(int a) {
		if ( a == 1 )
			this.isAccidental = true;
		else
			this.isAccidental = false;
	}
	
	public boolean isRest() {
		return isRest;
	}

	public boolean isAccidental(){
		return isAccidental;		
	}
	
	public void setRest(boolean isRest) {
		this.isRest = isRest;
	}
	
	public int getMusicsheet_id() {
		return musicsheet_id;
	}

	public void setMusicsheet_id(int musicsheet_id) {
		this.musicsheet_id = musicsheet_id;
	}

}
