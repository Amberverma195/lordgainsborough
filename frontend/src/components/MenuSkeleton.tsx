import React from 'react';
import styles from './MenuSkeleton.module.css';

const MenuSkeleton = () => {
  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div className={`${styles.skeleton} ${styles.title}`}></div>
        <div className={`${styles.skeleton} ${styles.subtitle}`}></div>
      </div>
      
      <div className={styles.filters}>
        {[1, 2, 3, 4, 5].map((i) => (
          <div key={i} className={`${styles.skeleton} ${styles.filterPill}`}></div>
        ))}
      </div>

      <div className={styles.grid}>
        {[1, 2, 3, 4, 5, 6].map((i) => (
          <div key={i} className={styles.card}>
            <div className={`${styles.skeleton} ${styles.image}`}></div>
            <div className={styles.details}>
              <div className={styles.topRow}>
                <div className={`${styles.skeleton} ${styles.name}`}></div>
                <div className={`${styles.skeleton} ${styles.price}`}></div>
              </div>
              <div className={`${styles.skeleton} ${styles.desc}`}></div>
              <div className={`${styles.skeleton} ${styles.desc} ${styles.short}`}></div>
              <div className={`${styles.skeleton} ${styles.button}`}></div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default MenuSkeleton;
