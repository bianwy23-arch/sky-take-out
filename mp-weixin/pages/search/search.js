var baseUrl = 'http://localhost:8080';

Page({
  data: {
    keyword: '',
    dishList: [],
    loading: false,
    searched: false
  },

  onInput: function(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch: function() {
    var keyword = this.data.keyword.trim();
    if (!keyword) {
      wx.showToast({ title: '请输入关键词', icon: 'none' });
      return;
    }
    var token = wx.getStorageSync('sky_token') || '';
    this.setData({ loading: true, searched: false, dishList: [] });
    wx.request({
      url: baseUrl + '/user/dish/search',
      method: 'GET',
      data: { keyword: keyword },
      header: { 'authentication': token },
      success: function(res) {
        if (res.data && res.data.code === 1) {
          this.setData({ dishList: res.data.data || [], searched: true });
        } else {
          wx.showToast({ title: '搜索失败', icon: 'none' });
          this.setData({ searched: true });
        }
      }.bind(this),
      fail: function() {
        wx.showToast({ title: '网络错误', icon: 'none' });
        this.setData({ searched: true });
      }.bind(this),
      complete: function() {
        this.setData({ loading: false });
      }.bind(this)
    });
  }
});
