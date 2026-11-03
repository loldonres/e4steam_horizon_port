package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.NoSuchElementException;

public final class IntDeque {
   private int[] data;
   private int head = 0;
   private int tail = 0;
   private int mask;

   public IntDeque() {
      this(4);
   }

   public IntDeque(int initialCapacity) {
      if (initialCapacity <= 0) {
         throw new IllegalArgumentException("The capacity must be positive and non-zero.");
      } else {
         if (!this.isPowerOfTwo(initialCapacity)) {
            initialCapacity = this.nextPowerOfTwo(initialCapacity);
         }

         this.data = new int[initialCapacity];
         this.mask = initialCapacity - 1;
      }
   }

   private boolean isPowerOfTwo(int n) {
      return (n & -n) == n;
   }

   private int nextPowerOfTwo(int n) {
      return Integer.highestOneBit(n) << 1;
   }

   public void clear() {
      this.head = 0;
      this.tail = 0;
   }

   public boolean isEmpty() {
      return this.tail == this.head;
   }

   public int size() {
      return this.tail >= this.head ? this.tail - this.head : this.data.length - this.head + this.tail;
   }

   public void compact() {
      if (this.tail == this.head) {
         this.data = new int[1];
         this.head = 0;
         this.tail = 0;
         this.mask = 0;
      } else {
         int size = this.size();
         int newCapacity = size + 1;
         if (!this.isPowerOfTwo(newCapacity)) {
            newCapacity = this.nextPowerOfTwo(newCapacity);
         }

         int[] newData = new int[newCapacity];
         if (this.tail > this.head) {
            System.arraycopy(this.data, this.head, newData, 0, this.tail - this.head);
         } else {
            int lenght1 = this.data.length - this.head;
            System.arraycopy(this.data, this.head, newData, 0, lenght1);
            System.arraycopy(this.data, 0, newData, lenght1, this.tail);
         }

         this.head = 0;
         this.tail = size;
         this.data = newData;
         this.mask = newData.length - 1;
      }
   }

   private void grow() {
      int newSize = this.data.length << 1;
      if (newSize < 0) {
         throw new IllegalStateException("IntDeque too big");
      } else {
         int[] newData = new int[newSize];
         int lenght1 = this.data.length - this.head;
         System.arraycopy(this.data, this.head, newData, 0, lenght1);
         System.arraycopy(this.data, 0, newData, lenght1, this.tail);
         this.head = 0;
         this.tail = this.data.length;
         this.data = newData;
         this.mask = newData.length - 1;
      }
   }

   public void addFirst(int element) {
      this.head = this.head - 1 & this.mask;
      this.data[this.head] = element;
      if (this.head == this.tail) {
         this.grow();
      }
   }

   public void addLast(int element) {
      this.data[this.tail] = element;
      this.tail = this.tail + 1 & this.mask;
      if (this.tail == this.head) {
         this.grow();
      }
   }

   public int get(int index) {
      if (index >= this.size()) {
         throw new NoSuchElementException("No element at index " + index);
      } else {
         return this.data[this.head + index & this.mask];
      }
   }

   public int getFirst() {
      if (this.tail == this.head) {
         throw new NoSuchElementException("Empty deque");
      } else {
         return this.data[this.head];
      }
   }

   public int getLast() {
      if (this.tail == this.head) {
         throw new NoSuchElementException("Empty deque");
      } else {
         return this.data[this.tail - 1 & this.mask];
      }
   }

   public int removeFirst() {
      if (this.tail == this.head) {
         throw new NoSuchElementException("Empty deque");
      } else {
         int element = this.data[this.head];
         this.head = this.head + 1 & this.mask;
         return element;
      }
   }

   public int removeLast() {
      if (this.tail == this.head) {
         throw new NoSuchElementException("Empty deque");
      } else {
         this.tail = this.tail - 1 & this.mask;
         return this.data[this.tail];
      }
   }
}
