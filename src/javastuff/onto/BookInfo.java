package javastuff.onto;

import jade.content.Concept;
import jade.content.onto.annotations.Slot;

/**
 * Created by Martin Pilat on 12.2.14.
 *
 * Information on a book - name and ID
 */
public class BookInfo implements Concept {

    private String bookName;
    private int bookID;

    @Slot(mandatory = true)
    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public int getBookID() {
        return bookID;
    }

    public void setBookID(int bookID) {
        this.bookID = bookID;
    }

    public String toString() {
        return "[" + bookName + "," + bookID + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((bookName == null) ? 0 : bookName.hashCode());
        result = prime * result + bookID;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        BookInfo other = (BookInfo) obj;
        if (bookName == null) {
            if (other.bookName != null)
                return false;
        } else if (!bookName.equals(other.bookName))
            return false;
        return bookID == other.bookID;
    }
}
