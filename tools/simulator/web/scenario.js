// Generic test map: four kingdoms in the corners, neutral nodes in the middle.
(function (root, factory) {
  const s = factory();
  if (typeof module === 'object' && module.exports) module.exports = s;
  else (root.SCENARIOS = root.SCENARIOS || {}).fourCorners = s;
})(typeof self !== 'undefined' ? self : this, function () {
  const kingdoms = [
    { id: 'dw', name: 'Ironhold Dwarves', color: '#B9772E', growth: 'normal', ai: 'balanced', food: 20, wealth: 1000, res: 200 },
    { id: 'el', name: 'Sylvan Court', color: '#2C8C8A', growth: 'slow', ai: 'defensive', food: 20, wealth: 1000, res: 200 },
    { id: 'hu', name: 'Kingdom of Aster', color: '#4169C8', growth: 'normal', ai: 'economic', food: 20, wealth: 1000, res: 200 },
    { id: 'go', name: 'Goblin Warrens', color: '#7E9A26', growth: 'fast', ai: 'aggressive', goblinRule: true, food: 20, wealth: 1000, res: 200 },
  ];
  const names = {
    dw: ['Karak Ostrum', 'Stonebrook', 'Greyhelm Hold', 'Deepvein Mine', 'Moss Terraces', 'Eastwatch'],
    el: ['Lethiriel', 'Silverglade', 'Thornwall Hold', 'Moonstone Dig', 'Dewfall Gardens', 'Westwatch'],
    hu: ['Asterhal', 'Millford', 'Redcliff Hold', 'Copperhill Mine', 'Barley Fields', 'Northwatch'],
    go: ['Gritmaw', 'Snagtown', 'Bonepile Hold', 'Muckpit Dig', 'Fungus Beds', 'Peekspire'],
  };
  // Layout for the top-left kingdom; the others are mirrored.
  const template = [
    { s: 'cap', type: 'capital', tier: 5, x: 110, y: 110, garrison: 30, defense: 10 },
    { s: 'town', type: 'town', tier: 2, x: 285, y: 85, garrison: 10, defense: 2 },
    { s: 'castle', type: 'castle', tier: 1, x: 250, y: 230, garrison: 15, defense: 6 },
    { s: 'mine', type: 'production', tier: 2, x: 85, y: 270, garrison: 5, defense: 1 },
    { s: 'farm', type: 'food', tier: 1, x: 405, y: 165, garrison: 5, defense: 1 },
    { s: 'tower', type: 'outpost', tier: 1, x: 385, y: 290, garrison: 5, defense: 3 },
  ];
  const mirror = { dw: [false, false], el: [true, false], hu: [false, true], go: [true, true] };
  const nodes = [];
  const edges = [];
  for (const k of kingdoms) {
    const [mx, my] = mirror[k.id];
    template.forEach((t, i) => {
      nodes.push({
        id: k.id + '_' + t.s, name: names[k.id][i], type: t.type, tier: t.tier, owner: k.id,
        garrison: t.garrison, defense: t.defense, morale: 10, devastation: 0,
        x: mx ? 1000 - t.x : t.x, y: my ? 700 - t.y : t.y,
      });
    });
    const e = (a, b) => edges.push([k.id + '_' + a, k.id + '_' + b]);
    e('cap', 'town'); e('cap', 'castle'); e('cap', 'mine'); e('town', 'farm');
    e('castle', 'farm'); e('castle', 'mine'); e('castle', 'tower'); e('farm', 'tower');
  }
  nodes.push(
    { id: 'n_market', name: 'Crossroads Market', type: 'town', tier: 2, owner: null, garrison: 15, defense: 3, x: 500, y: 350 },
    { id: 'n_river', name: 'Riverlands', type: 'food', tier: 2, owner: null, garrison: 8, defense: 1, x: 500, y: 150 },
    { id: 'n_quarry', name: 'Old Quarry', type: 'production', tier: 2, owner: null, garrison: 8, defense: 1, x: 500, y: 550 },
    { id: 'n_keep', name: 'Broken Keep', type: 'castle', tier: 0, owner: null, garrison: 0, defense: 0, x: 175, y: 350 },
    { id: 'n_fort', name: 'Sunken Fort', type: 'castle', tier: 1, owner: null, garrison: 10, defense: 5, x: 825, y: 350 },
  );
  edges.push(
    ['dw_farm', 'n_river'], ['el_farm', 'n_river'], ['hu_farm', 'n_quarry'], ['go_farm', 'n_quarry'],
    ['dw_tower', 'n_market'], ['el_tower', 'n_market'], ['hu_tower', 'n_market'], ['go_tower', 'n_market'],
    ['n_river', 'n_market'], ['n_quarry', 'n_market'],
    ['n_keep', 'dw_castle'], ['n_keep', 'hu_castle'], ['n_fort', 'el_castle'], ['n_fort', 'go_castle'],
    ['dw_mine', 'hu_mine'], ['el_mine', 'go_mine'],
    ['dw_castle', 'n_market'], ['el_castle', 'n_market'], ['hu_castle', 'n_market'], ['go_castle', 'n_market'],
    ['dw_town', 'n_river'], ['el_town', 'n_river'], ['hu_town', 'n_quarry'], ['go_town', 'n_quarry'],
  );
  return { name: 'Four Corners', kingdoms, nodes, edges };
});
