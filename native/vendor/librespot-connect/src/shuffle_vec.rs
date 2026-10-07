use rand::{Rng, SeedableRng, rngs::SmallRng};
use std::{
    ops::{Deref, DerefMut},
    vec::IntoIter,
};

#[derive(Debug, Clone, Default)]
pub struct ShuffleVec<T> {
    vec: Vec<T>,
    indices: Option<Vec<usize>>,
    /// This is primarily necessary to ensure that shuffle does not behave out of place.
    ///
    /// For that reason we swap the first track with the currently playing track. By that we ensure
    /// that the shuffle state is consistent between resets of the state because the first track is
    /// always the track with which we started playing when switching to shuffle.
    original_first_position: Option<usize>,
}

impl<T: PartialEq> PartialEq for ShuffleVec<T> {
    fn eq(&self, other: &Self) -> bool {
        self.vec == other.vec
    }
}

impl<T> Deref for ShuffleVec<T> {
    type Target = Vec<T>;

    fn deref(&self) -> &Self::Target {
        &self.vec
    }
}

impl<T> DerefMut for ShuffleVec<T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.vec.as_mut()
    }
}

impl<T> IntoIterator for ShuffleVec<T> {
    type Item = T;
    type IntoIter = IntoIter<T>;

    fn into_iter(self) -> Self::IntoIter {
        self.vec.into_iter()
    }
}

impl<T> From<Vec<T>> for ShuffleVec<T> {
    fn from(vec: Vec<T>) -> Self {
        Self {
            vec,
            original_first_position: None,
            indices: None,
        }
    }
}

impl<T> ShuffleVec<T> {
    pub fn shuffle_with_seed<F: Fn(&T) -> bool>(&mut self, seed: u64, is_first: F) {
        self.shuffle_with_rng(SmallRng::seed_from_u64(seed), is_first)
    }

    pub fn shuffle_with_rng<F: Fn(&T) -> bool>(&mut self, mut rng: impl Rng, is_first: F) {
        if self.vec.len() <= 1 {
            info!("skipped shuffling for less or equal one item");
            return;
        }

        if self.indices.is_some() {
            self.unshuffle()
        }

        let indices: Vec<_> = {
            (1..self.vec.len())
                .rev()
                .map(|i| rng.random_range(0..i + 1))
                .collect()
        };

        for (i, &rnd_ind) in (1..self.vec.len()).rev().zip(&indices) {
            self.vec.swap(i, rnd_ind);
        }

        self.indices = Some(indices);

        self.original_first_position = self.vec.iter().position(is_first);
        if let Some(first_pos) = self.original_first_position {
            self.vec.swap(0, first_pos)
        }
    }

    // SPOTIFYGOOD: pushing onto a shuffled vec (through DerefMut) made it longer than its
    // `indices`, so `unshuffle` stopped at its first lookup and left the order shuffled
    /// Appends the items at the end, also of the shuffled order, so that [ShuffleVec::unshuffle]
    /// still restores the original order (with the items at its end)
    ///
    /// The shuffle swapped the positions `len - 1` down to `1` with the recorded `indices`,
    /// newest position first. Every new position is recorded as swapped with itself, which keeps
    /// the existing ones in place.
    pub fn extend_keep_shuffle(&mut self, items: impl IntoIterator<Item = T>) {
        let old_len = self.vec.len();
        self.vec.extend(items);
        let new_len = self.vec.len();

        if let Some(old) = self.indices.take() {
            self.indices = Some((old_len..new_len).rev().chain(old).collect());
        }
    }

    // SPOTIFYGOOD: an update of a context that plays shuffled keeps its order, see
    // ConnectState::update_context
    /// Puts the items into the given order and records it as the shuffle, so that
    /// [ShuffleVec::unshuffle] restores the original order
    ///
    /// `order[k]` is the position (in the original order) of the item that goes to position `k`.
    /// The order is recorded as the swaps of the Fisher-Yates shuffle (see `shuffle_with_rng`)
    /// that produce it. Returns false, and changes nothing, if `order` isn't a permutation of
    /// the positions.
    pub fn shuffle_to_order(&mut self, order: &[usize]) -> bool {
        let len = self.vec.len();
        let mut seen = vec![false; len];
        if order.len() != len
            || order
                .iter()
                .any(|&p| p >= len || std::mem::replace(&mut seen[p], true))
        {
            return false;
        }

        self.unshuffle();

        // at[p]: the original position of the item now at p, pos: the inverse
        let mut at = (0..len).collect::<Vec<_>>();
        let mut pos = (0..len).collect::<Vec<_>>();
        let mut indices = Vec::with_capacity(len.saturating_sub(1));
        for i in (1..len).rev() {
            // positions above i are final, the item for i is at or below it
            let p = pos[order[i]];
            indices.push(p);
            self.vec.swap(i, p);
            let (a, b) = (at[i], at[p]);
            at.swap(i, p);
            pos[a] = p;
            pos[b] = i;
        }

        self.indices = Some(indices);
        self.original_first_position = None;
        true
    }

    pub fn unshuffle(&mut self) {
        let indices = match self.indices.take() {
            Some(indices) => indices,
            None => return,
        };

        if let Some(first_pos) = self.original_first_position {
            self.vec.swap(0, first_pos);
            self.original_first_position = None;
        }

        for i in 1..self.vec.len() {
            match indices.get(self.vec.len() - i - 1) {
                None => return,
                Some(n) => self.vec.swap(*n, i),
            }
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use rand::Rng;
    use std::ops::Range;

    fn base(range: Range<usize>) -> (ShuffleVec<usize>, u64) {
        let seed = rand::rng().random_range(0..10_000_000_000_000);

        let vec = range.collect::<Vec<_>>();
        (vec.into(), seed)
    }

    #[test]
    fn test_shuffle_without_first() {
        let (base_vec, seed) = base(0..100);

        let mut shuffled_vec = base_vec.clone();
        shuffled_vec.shuffle_with_seed(seed, |_| false);

        let mut different_shuffled_vec = base_vec.clone();
        different_shuffled_vec.shuffle_with_seed(seed, |_| false);

        assert_eq!(
            shuffled_vec, different_shuffled_vec,
            "shuffling with the same seed has the same result"
        );

        let mut unshuffled_vec = shuffled_vec.clone();
        unshuffled_vec.unshuffle();

        assert_eq!(
            base_vec, unshuffled_vec,
            "unshuffle restores the original state"
        );
    }

    #[test]
    fn test_shuffle_with_first() {
        const MAX_RANGE: usize = 200;

        let (base_vec, seed) = base(0..MAX_RANGE);
        let rand_first = rand::rng().random_range(0..MAX_RANGE);

        let mut shuffled_with_first = base_vec.clone();
        shuffled_with_first.shuffle_with_seed(seed, |i| i == &rand_first);

        assert_eq!(
            Some(&rand_first),
            shuffled_with_first.first(),
            "after shuffling the first is expected to be the given item"
        );

        let mut shuffled_without_first = base_vec.clone();
        shuffled_without_first.shuffle_with_seed(seed, |_| false);

        let mut switched_positions = Vec::with_capacity(2);
        for (i, without_first_value) in shuffled_without_first.iter().enumerate() {
            if without_first_value != &shuffled_with_first[i] {
                switched_positions.push(i);
            } else {
                assert_eq!(
                    without_first_value, &shuffled_with_first[i],
                    "shuffling with the same seed has the same result"
                );
            }
        }

        assert_eq!(
            switched_positions.len(),
            2,
            "only the switched positions should be different"
        );

        assert_eq!(
            shuffled_with_first[switched_positions[0]],
            shuffled_without_first[switched_positions[1]],
            "the switched values should be equal"
        );

        assert_eq!(
            shuffled_with_first[switched_positions[1]],
            shuffled_without_first[switched_positions[0]],
            "the switched values should be equal"
        )
    }

    // SPOTIFYGOOD: see ShuffleVec::shuffle_to_order
    #[test]
    fn test_shuffle_to_order() {
        use rand::seq::SliceRandom;

        for len in [0, 1, 2, 3, 10, 200] {
            let mut order = (0..len).collect::<Vec<_>>();
            order.shuffle(&mut rand::rng());
            let items = (0..len).map(|i| i * 10).collect::<Vec<_>>();

            // also from an already shuffled vec
            let mut vec: ShuffleVec<usize> = items.clone().into();
            vec.shuffle_with_seed(7, |i| *i == 0);
            assert!(vec.shuffle_to_order(&order));
            let expected = order.iter().map(|&p| items[p]).collect::<Vec<_>>();
            assert_eq!(*vec, expected);

            vec.unshuffle();
            assert_eq!(*vec, items);
        }

        // not a permutation: unchanged
        let mut vec: ShuffleVec<usize> = vec![0, 1, 2].into();
        assert!(!vec.shuffle_to_order(&[0, 0, 1]));
        assert!(!vec.shuffle_to_order(&[0, 1]));
        assert!(!vec.shuffle_to_order(&[0, 1, 3]));
        assert_eq!(*vec, vec![0, 1, 2]);
    }

    // SPOTIFYGOOD: see ShuffleVec::extend_keep_shuffle
    #[test]
    fn test_extend_keeps_unshuffle_working() {
        for (len, added) in [(3, 2), (100, 1), (100, 57), (2, 10)] {
            let (base_vec, seed) = base(0..len);
            let first = rand::rng().random_range(0..len);

            let mut vec = base_vec.clone();
            vec.shuffle_with_seed(seed, |i| i == &first);
            let shuffled = vec.to_vec();

            vec.extend_keep_shuffle(len..len + added);
            assert_eq!(vec[..len], shuffled, "the shuffled order is kept");
            assert_eq!(
                vec[len..],
                (len..len + added).collect::<Vec<_>>(),
                "the items are appended"
            );

            vec.unshuffle();
            assert_eq!(*vec, (0..len + added).collect::<Vec<_>>());

            // and it shuffles again from the right order
            let mut reshuffled = vec.clone();
            reshuffled.shuffle_with_seed(seed, |_| false);
            let mut fresh: ShuffleVec<_> = (0..len + added).collect::<Vec<_>>().into();
            fresh.shuffle_with_seed(seed, |_| false);
            assert_eq!(reshuffled, fresh);
        }

        // and after an order was set
        let mut ordered: ShuffleVec<usize> = (0..10).collect::<Vec<_>>().into();
        assert!(ordered.shuffle_to_order(&[3, 1, 4, 0, 9, 2, 6, 5, 8, 7]));
        ordered.extend_keep_shuffle(10..12);
        ordered.unshuffle();
        assert_eq!(*ordered, (0..12).collect::<Vec<_>>());

        // nothing to keep for a vec that was never shuffled (or had a single item)
        let mut single: ShuffleVec<usize> = vec![0].into();
        single.shuffle_with_seed(1, |_| false);
        single.extend_keep_shuffle(1..4);
        single.unshuffle();
        assert_eq!(*single, vec![0, 1, 2, 3]);
    }
}
